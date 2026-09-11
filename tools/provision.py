#!/usr/bin/env python3
"""Offline user provisioning. Writes secrets only to the requested profile file."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import tempfile


def write_private(path, value):
    path = Path(path)
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix=".sever-")
    try:
        with os.fdopen(fd, "w") as out:
            json.dump(value, out, indent=2)
            out.write("\n")
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--users", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--user", required=True)
    p.add_argument("--host", required=True, help="TLS certificate DNS hostname")
    p.add_argument("--port", type=int, default=443)
    p.add_argument("--path", default="/api/session")
    a = p.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", a.user) or not 1 <= a.port <= 65535:
        p.error("invalid user or port")
    if not re.fullmatch(r"[A-Za-z0-9.-]{1,253}", a.host):
        p.error("invalid hostname")
    if not re.fullmatch(r"/[A-Za-z0-9][A-Za-z0-9/_-]{0,126}", a.path) or a.path.startswith("/_"):
        p.error("invalid endpoint path")
    if Path(a.users).resolve() == Path(a.out).resolve() or Path(a.out).exists():
        p.error("output must be a new, separate file")
    users = json.loads(Path(a.users).read_text()) if Path(a.users).exists() else {}
    if a.user in users:
        p.error("user already exists; use a new device name")
    used = {row["ip"] for row in users.values()}
    address = next((f"10.77.0.{n}" for n in range(2, 255) if f"10.77.0.{n}" not in used), None)
    if address is None:
        p.error("address pool exhausted")
    token = secrets.token_urlsafe(32)
    users[a.user] = {"ip": address, "token_sha256": hashlib.sha256(token.encode()).hexdigest()}
    profile = {"version": 2, "protocol": "sever1", "transport": "wss", "name": a.user,
               "endpoints": [{"host": a.host, "port": a.port, "path": a.path}], "user": a.user, "token": token}
    write_private(a.out, profile)
    write_private(a.users, users)
    print("Created profile and server user record. Restart gateway to load users.")


if __name__ == "__main__":
    main()
