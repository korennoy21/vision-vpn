#!/usr/bin/env python3
"""Local interactive setup; writes private files, never runs remote commands."""
import argparse
import getpass
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def hostname(prompt):
    value = input(prompt).strip().lower()
    if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', value) or '.' not in value or '..' in value:
        raise SystemExit('Введите доменное имя без https://, порта и пути')
    return value

def write_new(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f: f.write(data)

p = argparse.ArgumentParser()
p.add_argument('mode', choices=['panel', 'node'])
a = p.parse_args()
os.umask(0o077)
if a.mode == 'panel':
    domain = hostname('Домен панели (panel.example.com): ')
    write_new(ROOT / 'panel.env', f'PANEL_DOMAIN={domain}\n')
    directory = ROOT / 'data'; directory.mkdir(mode=0o700, exist_ok=True)
    if os.geteuid() == 0:
        os.chown(directory, 10001, 10001)
    print('Создан panel.env. Далее выполните сборку и init администратора.')
else:
    if (ROOT/'node.env').exists() or (ROOT/'node.json').exists():
        raise SystemExit('node.env/node.json уже существуют; измените их вручную, сохранив токен')
    domain = hostname('Домен VPN-сервера (vpn.example.com): ')
    port = int(input('Публичный TLS-порт [443]: ').strip() or '443')
    if not 1 <= port <= 65535: raise SystemExit('Некорректный порт')
    control = hostname('Домен панели (panel.example.com): ')
    token = getpass.getpass('node_token из панели (ввод скрыт): ').strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]{43}', token): raise SystemExit('Некорректный токен')
    write_new(ROOT/'node.json', json.dumps({'control_url':'https://'+control,'node_token':token},indent=2)+'\n')
    write_new(ROOT/'node.env',f'VPN_DOMAIN={domain}\nVPN_PORT={port}\n')
    print('Созданы node.env и node.json. Токен не выводится.')
