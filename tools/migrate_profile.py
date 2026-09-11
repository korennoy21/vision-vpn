#!/usr/bin/env python3
"""Offline v1 -> v2 migration. Credentials unchanged; explicit HTTPS port required."""
import argparse
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sever.profile import validate_profile
from provision import write_private


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("--out", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--path", default="/api/session")
    args = parser.parse_args()
    source, target = Path(args.source), Path(args.out)
    if target.exists() or source.resolve() == target.resolve():
        parser.error("output must be a new file")
    if source.stat().st_size > 65536:
        parser.error("profile too large")
    profile = json.loads(source.read_text())
    if profile.get("version") != 1 or profile.get("protocol") != "sever1":
        parser.error("SEVER-1 profile version 1 required")
    profile["version"], profile["transport"] = 2, "wss"
    for endpoint in profile["endpoints"]:
        endpoint["port"], endpoint["path"] = args.port, args.path
    validate_profile(profile)
    write_private(target, profile)
    print("Profile migrated; credentials unchanged. Configure the matching HTTPS endpoints before connecting.")


if __name__ == "__main__":
    main()
