#!/usr/bin/env python3
"""Print nftables rules by default. --apply adds only dedicated sever tables."""
import argparse
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wan", required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--remove", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,15}", args.wan):
        parser.error("invalid WAN interface")
    if args.remove:
        rules = "delete table inet sever_filter\ndelete table ip sever_nat\n"
    else:
        rules = '''table inet sever_filter {
 chain forward {
  type filter hook forward priority -10; policy accept;
  iifname "sever0" ip saddr != 10.77.0.0/24 drop
  iifname "sever0" oifname != "%s" drop
  iifname "sever0" oifname "%s" accept
  oifname "sever0" ct state established,related accept
  oifname "sever0" drop
 }
}
table ip sever_nat {
 chain postrouting {
  type nat hook postrouting priority srcnat; policy accept;
  oifname "%s" ip saddr 10.77.0.0/24 masquerade
 }
}
''' % (args.wan, args.wan, args.wan)
    if not args.apply:
        print(rules)
        return
    # Existing unrelated tables are never flushed. Existing sever tables require explicit removal.
    if not args.remove:
        for family, table in (("inet", "sever_filter"), ("ip", "sever_nat")):
            check = subprocess.run(["nft", "list", "table", family, table], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if check.returncode == 0:
                parser.error("Sever table already exists; explicitly remove it before applying new rules")
    subprocess.run(["nft", "--check", "--file", "-"], input=rules, text=True, check=True)
    subprocess.run(["nft", "--file", "-"], input=rules, text=True, check=True)


if __name__ == "__main__":
    main()
