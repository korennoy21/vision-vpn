#!/usr/bin/env python3
"""Make a separate Linux obfs4 profile from an existing SEVER v2 profile."""
import argparse
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sever.profile import validate_profile
from provision import write_private


def main():
    p = argparse.ArgumentParser()
    p.add_argument("source")
    p.add_argument("--out", required=True)
    p.add_argument("--bridge-ip", required=True)
    p.add_argument("--bridge-port", type=int, required=True)
    p.add_argument("--server-name", required=True)
    p.add_argument("--obfs4-cert", required=True)
    p.add_argument("--iat-mode", type=int, choices=(0, 1, 2), required=True)
    p.add_argument("--proxy-port", type=int, required=True)
    a = p.parse_args()
    source, target = Path(a.source), Path(a.out)
    if target.exists() or source.resolve() == target.resolve():
        p.error("output must be a new, separate file")
    if source.stat().st_size > 65536:
        p.error("profile too large")
    profile = validate_profile(json.loads(source.read_text()))
    profile["transport"] = "obfs4"
    profile["local_proxy"] = {"host": "127.0.0.1", "port": a.proxy_port}
    profile["endpoints"] = [{"host": a.bridge_ip, "port": a.bridge_port,
        "server_name": a.server_name, "cert": a.obfs4_cert, "iat_mode": a.iat_mode}]
    validate_profile(profile)
    write_private(target, profile)
    print("Created Linux obfs4 profile; credentials preserved; external PT required.")


if __name__ == "__main__":
    main()
