import argparse
import base64
import asyncio
from collections import defaultdict, deque
from datetime import datetime
import getpass
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time
from urllib.parse import urlsplit
from aiohttp import web
from .store import Store, digest, password_hash, password_ok

STATIC = Path(__file__).parent / 'static'

def text(value, field, limit=80):
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError(f'Некорректное поле: {field}')
    return value.strip()


def node_input(body):
    name = text(body.get('name'), 'Название')
    host = text(body.get('host'), 'Адрес', 253).lower()
    if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', host) or '..' in host:
        raise ValueError('Укажите домен сервера без https:// и пути')
    port = body.get('port', 443)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('Порт должен быть от 1 до 65535')
    enabled = body.get('enabled', True)
    if type(enabled) is not bool:
        raise ValueError('Некорректное состояние сервера')
    return name, host, port, int(enabled)


def client_input(body, db):
    name = text(body.get('name'), 'Название')
    ids = body.get('node_ids')
    if not isinstance(ids, list) or not 1 <= len(ids) <= 16 or any(type(i) is not int for i in ids) or len(set(ids)) != len(ids):
        raise ValueError('Выберите от 1 до 16 разных серверов')
    for node_id in ids:
        if not db.execute('SELECT 1 FROM nodes WHERE id=?', (node_id,)).fetchone():
            raise ValueError('Сервер не найден')
    enabled = body.get('enabled', True)
    if type(enabled) is not bool:
        raise ValueError('Некорректное состояние клиента')
    expires = body.get('expires')
    if expires is not None and not isinstance(expires, str):
        raise ValueError('Некорректный срок доступа')
    if expires:
        dt = datetime.fromisoformat(expires.replace('Z', '+00:00'))
        if dt.tzinfo is None:
            raise ValueError('Срок доступа должен включать часовой пояс')
        expires = dt.timestamp()
    else:
        expires = None
    return name, ids, int(enabled), expires


def make_app(store, origin, development=False):
    u = urlsplit(origin)
    if u.scheme not in ('https', 'http') or not u.hostname or u.path or u.query or u.fragment or u.username or u.password:
        raise ValueError('PUBLIC_URL должен иметь вид https://panel.example.com без / в конце')
    if not development and u.scheme != 'https':
        raise ValueError('Панели требуется HTTPS')
    cookie = 'vision_admin'
    attempts, global_attempts = {}, deque()
    dummy = password_hash('not-an-administrator-password')
    login_slots = asyncio.Semaphore(2)

    @web.middleware
    async def middleware(request, handler):
        try:
            if request.path.startswith('/api/') and request.path not in ('/api/login', '/api/client-telemetry'):
                auth = request.headers.get('Authorization', '')
                api_identity = None
                if auth.startswith('Bearer vapi_'):
                    api_identity = store.api_auth(auth[7:])
                if api_identity:
                    request['auth_type'] = 'api'
                    request['csrf'] = ''
                    request['user'] = 'api:' + api_identity['name']
                    request['role'] = 'admin' if api_identity['scope'] == 'write' else 'viewer'
                    request['api_scope'] = api_identity['scope']
                    if request.method not in ('GET', 'HEAD') and api_identity['scope'] != 'write':
                        raise web.HTTPForbidden(text='API-токен имеет только чтение')
                else:
                    token = request.cookies.get(cookie, '')
                    with store.db() as db:
                        session = db.execute('''SELECT s.csrf,s.user,a.role FROM sessions s
                          JOIN admins a ON a.name=s.user WHERE s.hash=? AND s.expires>?''', (digest(token), time.time())).fetchone()
                    if not session:
                        raise web.HTTPUnauthorized(text='Требуется вход')
                    request['auth_type'] = 'session'
                    request['csrf'] = session['csrf']
                    request['user'] = session['user']
                    request['role'] = session['role']
                    if request.method not in ('GET', 'HEAD'):
                        if session['role'] == 'viewer' and request.path != '/api/logout':
                            raise web.HTTPForbidden(text='Роль только для просмотра')
                        if not hmac.compare_digest(request.headers.get('X-CSRF-Token', ''), session['csrf']):
                            raise web.HTTPForbidden(text='Обновите страницу и повторите действие')
                        if request.headers.get('Origin') != origin:
                            raise web.HTTPForbidden(text='Недопустимый источник запроса')
            response = await handler(request)
        except web.HTTPException as e:
            response = web.json_response({'error': e.text}, status=e.status)
        except (ValueError, TypeError, KeyError) as e:
            response = web.json_response({'error': str(e) if isinstance(e, ValueError) else 'Некорректные данные'}, status=400)
        except sqlite3.IntegrityError:
            response = web.json_response({'error': 'Конфликт данных; обновите страницу'}, status=409)
        response.headers.update({'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
            'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY',
            'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"})
        return response

    app = web.Application(middlewares=[middleware], client_max_size=262144)

    async def body(request):
        if request.content_type != 'application/json':
            raise web.HTTPUnsupportedMediaType(text='Требуется JSON')
        value = await request.json()
        if not isinstance(value, dict):
            raise ValueError('Требуется объект JSON')
        return value

    async def login(request):
        if request.headers.get('Origin') != origin:
            raise web.HTTPForbidden(text='Недопустимый источник запроса')
        data = await body(request)
        now = time.time()
        # Caddy sets this header and the backend binds loopback; never use this
        # header on a publicly reachable backend.
        remote = request.headers.get('X-Forwarded-For', request.remote or '').split(',')[0].strip()
        while global_attempts and global_attempts[0] < now - 60:
            global_attempts.popleft()
        attempts.clear() if len(attempts) > 4096 else None
        recent = attempts.setdefault(remote, deque())
        while recent and recent[0] < now - 60:
            recent.popleft()
        if len(recent) >= 5 or len(global_attempts) >= 40:
            raise web.HTTPTooManyRequests(text='Слишком много попыток; повторите через минуту')
        recent.append(now); global_attempts.append(now)
        username = text(data.get('username'), 'Логин', 64)
        password = data.get('password')
        if not isinstance(password, str) or not 1 <= len(password) <= 256:
            raise ValueError('Некорректный пароль')
        with store.db() as db:
            row = db.execute('SELECT password,role FROM admins WHERE name=?', (username,)).fetchone()
        async with login_slots:
            valid = await asyncio.to_thread(password_ok, password, row['password'] if row else dummy)
        if not valid or not row:
            raise web.HTTPUnauthorized(text='Неверный логин или пароль')
        token, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(24)
        with store.db() as db:
            current = db.execute('SELECT password,role FROM admins WHERE name=?', (username,)).fetchone()
            if not current or current['password'] != row['password']:
                raise web.HTTPUnauthorized(text='Повторите вход')
            db.execute('DELETE FROM sessions WHERE expires<=?', (now,))
            db.execute('INSERT INTO sessions(hash,csrf,expires,user) VALUES(?,?,?,?)', (digest(token), csrf, now + 43200, username))
            store.audit(db, 'admin.login', username)
        response = web.json_response({'csrf': csrf, 'user': username, 'role': row['role']})
        response.set_cookie(cookie, token, httponly=True, secure=not development, samesite='Strict', max_age=43200, path='/')
        return response

    async def me(request):
        return web.json_response({'csrf': request['csrf'], 'version': '1.2.0', 'product': 'VISION Control', 'user': request.get('user',''), 'role': request.get('role','viewer')})

    async def logout(request):
        with store.db() as db:
            db.execute('DELETE FROM sessions WHERE hash=?', (digest(request.cookies.get(cookie, '')),))
        response = web.json_response({'ok': True}); response.del_cookie(cookie, path='/')
        return response

    async def snapshot(request):
        telemetry = store.telemetry_latest()
        with store.db() as db:
            nodes = [dict(r) for r in db.execute('SELECT id,name,host,port,enabled,last_seen,online FROM nodes ORDER BY id')]
            clients = [dict(r) for r in db.execute('SELECT id,name,user,ip,enabled,expires,created FROM clients ORDER BY id DESC')]
            for c in clients:
                c['node_ids'] = [r[0] for r in db.execute('SELECT node_id FROM assignments WHERE client_id=? ORDER BY node_id', (c['id'],))]
                c['telemetry'] = telemetry.get(c['user'])
            events = [dict(r) for r in db.execute('SELECT * FROM events ORDER BY id DESC LIMIT 40')]
        owner = request.get('role') == 'owner'
        return web.json_response({'nodes': nodes, 'clients': clients, 'events': events,
            'settings': store.get_settings(), 'engines': store.engines(), 'routes': store.routes(),
            'dns_rules': store.dns_rules(), 'backups': store.backups(),
            'admins': store.admins() if owner else [], 'api_keys': store.api_tokens() if owner else [],
            'me': {'user': request.get('user',''), 'role': request.get('role','viewer')}, 'now': time.time()})

    def client_basic_auth(request):
        header = request.headers.get('Authorization', '')
        if not header.startswith('Basic '):
            raise web.HTTPUnauthorized(text='Unauthorized')
        try:
            decoded = base64.b64decode(header[6:], validate=True).decode('utf-8')
            user, sep, token = decoded.partition(':')
        except (ValueError, UnicodeDecodeError):
            raise web.HTTPUnauthorized(text='Unauthorized')
        if not sep or not store.client_auth(user, token):
            raise web.HTTPUnauthorized(text='Unauthorized')
        return user

    async def client_telemetry(request):
        user = client_basic_auth(request)
        data = await body(request)
        def sval(name, limit, default=''):
            value = data.get(name, default)
            if not isinstance(value, str) or len(value) > limit:
                raise ValueError('Некорректная телеметрия')
            return value
        def counter(name, maximum=(1 << 63) - 1):
            value = data.get(name, 0)
            if type(value) is not int or not 0 <= value <= maximum:
                raise ValueError('Некорректная телеметрия')
            return value
        platform = sval('platform', 16, 'android')
        if platform not in ('android', 'ios'):
            raise ValueError('Некорректная платформа')
        network = sval('network', 16, 'unknown')
        if network not in ('wifi', 'cellular', 'ethernet', 'other', 'offline', 'unknown'):
            raise ValueError('Некорректная сеть')
        rtt = data.get('rtt_ms')
        if rtt is not None and (type(rtt) is not int or not 0 <= rtt <= 3_600_000):
            raise ValueError('Некорректный RTT')
        values = {
            'platform': platform, 'engine': sval('engine', 32, 'vision'),
            'transport': sval('transport', 32, 'wss'), 'state': sval('state', 48, 'connected'),
            'endpoint': sval('endpoint', 320), 'network': network,
            'rx': counter('rx'), 'tx': counter('tx'), 'rtt_ms': rtt,
            'reconnects': counter('reconnects', 1_000_000), 'app_version': sval('app_version', 40),
        }
        at = store.save_client_telemetry(user, values)
        return web.json_response({'ok': True, 'at': at})

    async def telemetry_history(request):
        client_id = int(request.match_info['id'])
        with store.db() as db:
            row = db.execute('SELECT user FROM clients WHERE id=?', (client_id,)).fetchone()
        if not row:
            raise web.HTTPNotFound(text='Клиент не найден')
        return web.json_response({'samples': store.telemetry_history(row['user'], request.query.get('limit', 120))})

    async def settings_save(request):
        data = await body(request)
        dns_mode = data.get('dns_mode', 'node')
        if dns_mode not in ('node', 'custom'):
            raise ValueError('Некорректный режим DNS')
        custom_dns = str(data.get('custom_dns', '1.1.1.1')).strip()
        if len(custom_dns) > 128:
            raise ValueError('DNS слишком длинный')
        mtu = int(data.get('default_mtu', 1280))
        if not 1000 <= mtu <= 1500:
            raise ValueError('MTU должен быть 1000–1500')
        allow_local = bool(data.get('allow_local_network', False))
        reconnect = data.get('reconnect_mode', 'automatic')
        if reconnect not in ('automatic', 'manual'):
            raise ValueError('Некорректная политика переподключения')
        retention = int(data.get('audit_retention_days', 30))
        telemetry_retention = int(data.get('telemetry_retention_days', 7))
        if not 1 <= retention <= 3650:
            raise ValueError('Хранение журнала: 1–3650 дней')
        if not 1 <= telemetry_retention <= 365:
            raise ValueError('Хранение телеметрии: 1–365 дней')
        preferred = data.get('preferred_engine', 'auto')
        if preferred not in ('auto','vision','wireguard','amneziawg','openvpn','xray'):
            raise ValueError('Некорректный основной движок')
        kill_switch = data.get('kill_switch', True)
        if type(kill_switch) is not bool:
            raise ValueError('Некорректный Kill Switch')
        activity_enabled = data.get('activity_enabled', True)
        activity_log_ips = data.get('activity_log_ips', True)
        if type(activity_enabled) is not bool or type(activity_log_ips) is not bool:
            raise ValueError('Некорректная настройка активности')
        activity_retention = int(data.get('activity_retention_days', 30))
        if not 1 <= activity_retention <= 365:
            raise ValueError('Хранение активности: 1–365 дней')
        saved = store.save_settings({
            'dns_mode': dns_mode, 'custom_dns': custom_dns, 'default_mtu': str(mtu),
            'allow_local_network': '1' if allow_local else '0', 'reconnect_mode': reconnect,
            'audit_retention_days': str(retention), 'telemetry_retention_days': str(telemetry_retention),
            'preferred_engine': preferred, 'kill_switch': '1' if kill_switch else '0', 'future_multi_protocol': '1',
            'activity_enabled': '1' if activity_enabled else '0',
            'activity_retention_days': str(activity_retention),
            'activity_log_ips': '1' if activity_log_ips else '0',
        })
        return web.json_response({'ok': True, 'settings': saved})

    async def activity_list(request):
        client_id = request.query.get('client_id') or None
        hours = request.query.get('hours', 24)
        limit = request.query.get('limit', 250)
        result = store.activity_summary(client_id=client_id, hours=hours, limit=limit)
        result['config'] = store.activity_config()
        return web.json_response(result)

    async def activity_clear(request):
        data = await body(request)
        client_id = data.get('client_id')
        count = store.clear_activity(client_id)
        return web.json_response({'ok': True, 'deleted': count})

    def require_owner(request):
        if request.get('role') != 'owner':
            raise web.HTTPForbidden(text='Требуются права владельца')

    async def engine_save(request):
        data = await body(request)
        engine = request.match_info['engine']
        values = store.save_engine(engine, data.get('enabled'), data.get('priority'), data.get('mode','auto'))
        return web.json_response({'ok': True, 'engines': values})

    async def route_save(request):
        data = await body(request)
        rule_id = int(request.match_info['id']) if 'id' in request.match_info else None
        return web.json_response({'id': store.save_route(data, rule_id)})

    async def route_delete(request):
        store.delete_route(int(request.match_info['id']))
        return web.json_response({'ok': True})

    async def dns_rule_save(request):
        data = await body(request)
        rule_id = int(request.match_info['id']) if 'id' in request.match_info else None
        return web.json_response({'id': store.save_dns_rule(data, rule_id)})

    async def dns_rule_delete(request):
        store.delete_dns_rule(int(request.match_info['id']))
        return web.json_response({'ok': True})

    async def backup_create(request):
        return web.json_response({'ok': True, 'name': store.create_backup()})

    async def backup_download(request):
        path = store.backup_path(request.match_info['name'])
        return web.FileResponse(path, headers={'Content-Disposition': f'attachment; filename="{path.name}"'})

    async def backup_delete(request):
        store.delete_backup(request.match_info['name'])
        return web.json_response({'ok': True})

    async def admin_create(request):
        require_owner(request)
        data = await body(request)
        store.add_admin(data.get('name'), data.get('password'), data.get('role'))
        return web.json_response({'ok': True})

    async def admin_password(request):
        require_owner(request)
        data = await body(request)
        store.set_admin_password(request.match_info['name'], data.get('password'))
        return web.json_response({'ok': True})

    async def admin_delete(request):
        require_owner(request)
        store.delete_admin(request.match_info['name'], request.get('user',''))
        return web.json_response({'ok': True})

    async def api_token_create(request):
        require_owner(request)
        data = await body(request)
        token = store.create_api_token(data.get('name'), data.get('scope'))
        return web.json_response({'ok': True, 'token': token})

    async def api_token_revoke(request):
        require_owner(request)
        store.revoke_api_token(int(request.match_info['id']))
        return web.json_response({'ok': True})

    async def node_save(request):
        data = await body(request)
        values = node_input(data)
        node_id = int(request.match_info['id']) if 'id' in request.match_info else None
        with store.db() as db:
            if node_id:
                cursor = db.execute('UPDATE nodes SET name=?,host=?,port=?,enabled=? WHERE id=?', (*values, node_id))
                if not cursor.rowcount: raise web.HTTPNotFound(text='Сервер не найден')
            else:
                if db.execute('SELECT COUNT(*) FROM nodes').fetchone()[0] >= 64:
                    raise ValueError('В этом выпуске максимум 64 сервера')
                token = secrets.token_urlsafe(32)
                cursor = db.execute('INSERT INTO nodes(name,host,port,enabled,token_hash,token_enc) VALUES(?,?,?,?,?,?)',
                    (*values, digest(token), store.seal(token)))
                node_id = cursor.lastrowid
            store.audit(db, 'node.saved', node_id)
        return web.json_response({'id': node_id})

    async def node_secret(request):
        with store.db() as db:
            row = db.execute('SELECT token_enc FROM nodes WHERE id=?', (int(request.match_info['id']),)).fetchone()
            if not row: raise web.HTTPNotFound(text='Сервер не найден')
            store.audit(db, 'node.credentials_viewed', request.match_info['id'])
        return web.json_response({'control_url': origin, 'node_token': store.unseal(row['token_enc'])})

    async def client_save(request):
        data = await body(request)
        client_id = int(request.match_info['id']) if 'id' in request.match_info else None
        with store.db() as db:
            db.execute('BEGIN IMMEDIATE')
            name, nodes, enabled, expires = client_input(data, db)
            if client_id:
                cursor = db.execute('UPDATE clients SET name=?,enabled=?,expires=? WHERE id=?', (name, enabled, expires, client_id))
                if not cursor.rowcount: raise web.HTTPNotFound(text='Клиент не найден')
            else:
                used = {r[0] for r in db.execute('SELECT ip FROM clients')}
                ip = next((f'10.77.0.{n}' for n in range(2, 255) if f'10.77.0.{n}' not in used), None)
                if not ip: raise ValueError('Пул из 253 устройств исчерпан')
                token, sub = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
                cursor = db.execute('''INSERT INTO clients(name,user,ip,enabled,expires,token_hash,token_enc,subscription_hash,subscription_enc,created)
                  VALUES(?,?,?,?,?,?,?,?,?,?)''', (name, 'device_' + secrets.token_hex(8), ip, enabled, expires,
                    digest(token), store.seal(token), digest(sub), store.seal(sub), time.time()))
                client_id = cursor.lastrowid
            db.execute('DELETE FROM assignments WHERE client_id=?', (client_id,))
            db.executemany('INSERT INTO assignments VALUES(?,?)', [(client_id, n) for n in nodes])
            store.audit(db, 'client.saved', client_id)
        return web.json_response({'id': client_id})

    async def client_access(request):
        client_id = int(request.match_info['id'])
        data = await body(request)
        if type(data.get('enabled')) is not bool:
            raise ValueError('Некорректное состояние')
        with store.db() as db:
            cursor = db.execute('UPDATE clients SET enabled=? WHERE id=?', (int(data['enabled']), client_id))
            if not cursor.rowcount: raise web.HTTPNotFound(text='Клиент не найден')
            store.audit(db, 'client.enabled' if data['enabled'] else 'client.disabled', client_id)
        return web.json_response({'ok': True})

    async def rotate(request):
        client_id = int(request.match_info['id'])
        token, sub = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        with store.db() as db:
            cursor = db.execute('UPDATE clients SET token_hash=?,token_enc=?,subscription_hash=?,subscription_enc=? WHERE id=?',
                (digest(token), store.seal(token), digest(sub), store.seal(sub), client_id))
            if not cursor.rowcount: raise web.HTTPNotFound(text='Клиент не найден')
            store.audit(db, 'client.keys_rotated', client_id)
        return web.json_response({'ok': True})

    async def client_profile(request):
        client_id = int(request.match_info['id'])
        profile = store.profile(client_id, origin)
        with store.db() as db: store.audit(db, 'client.profile_viewed', client_id)
        return web.json_response(profile)

    async def subscription(request):
        value = request.match_info['token']
        if not re.fullmatch(r'[A-Za-z0-9_-]{43}', value):
            raise web.HTTPNotFound(text='Профиль не найден')
        with store.db() as db:
            row = db.execute('SELECT id FROM clients WHERE subscription_hash=?', (digest(value),)).fetchone()
        if not row: raise web.HTTPNotFound(text='Профиль не найден')
        try: profile = store.profile(row['id'], origin)
        except ValueError: raise web.HTTPGone(text='Доступ отключён, истёк или нет включённых серверов')
        return web.json_response(profile, headers={'Content-Disposition': 'attachment; filename="vision.profile.json"'})

    def agent_node(request):
        header = request.headers.get('Authorization', '')
        if not re.fullmatch(r'Bearer [A-Za-z0-9_-]{43}', header):
            raise web.HTTPUnauthorized(text='Unauthorized')
        with store.db() as db:
            row = db.execute('SELECT id FROM nodes WHERE token_hash=?', (digest(header[7:]),)).fetchone()
        if not row: raise web.HTTPUnauthorized(text='Unauthorized')
        return row['id']

    async def agent_config(request):
        node_id = agent_node(request)
        activity = store.activity_config()
        return web.json_response({'users': store.active_users(node_id), 'lease_seconds': 60, 'path': '/api/session',
            'activity': {'enabled': activity['enabled'], 'log_ips': activity['log_ips']}})

    async def agent_activity(request):
        node_id = agent_node(request)
        data = await body(request)
        events = data.get('events')
        saved = store.save_activity(node_id, events)
        return web.json_response({'ok': True, 'saved': saved})

    async def agent_status(request):
        node_id = agent_node(request)
        data = await body(request)
        online = data.get('online')
        if type(online) is not int or not 0 <= online <= 253:
            raise ValueError('Invalid online count')
        with store.db() as db:
            db.execute('UPDATE nodes SET last_seen=?,online=? WHERE id=?', (time.time(), online, node_id))
        return web.json_response({'ok': True})

    async def health(request):
        with store.db() as db: db.execute('SELECT 1').fetchone()
        return web.json_response({'ok': True})

    async def asset(request):
        name = request.match_info.get('name', 'index.html')
        if name not in ('index.html', 'app.js', 'style.css'):
            raise web.HTTPNotFound(text='Not found')
        return web.FileResponse(STATIC / name)

    app.router.add_post('/api/login', login)
    app.router.add_post('/api/client-telemetry', client_telemetry)
    app.router.add_get('/api/me', me)
    app.router.add_post('/api/logout', logout)
    app.router.add_get('/api/state', snapshot)
    app.router.add_get('/api/activity', activity_list)
    app.router.add_delete('/api/activity', activity_clear)
    app.router.add_put('/api/settings', settings_save)
    app.router.add_put('/api/engines/{engine}', engine_save)
    app.router.add_post('/api/routes', route_save)
    app.router.add_put('/api/routes/{id:\\d+}', route_save)
    app.router.add_delete('/api/routes/{id:\\d+}', route_delete)
    app.router.add_post('/api/dns-rules', dns_rule_save)
    app.router.add_put('/api/dns-rules/{id:\\d+}', dns_rule_save)
    app.router.add_delete('/api/dns-rules/{id:\\d+}', dns_rule_delete)
    app.router.add_post('/api/backups', backup_create)
    app.router.add_get('/api/backups/{name}', backup_download)
    app.router.add_delete('/api/backups/{name}', backup_delete)
    app.router.add_post('/api/admins', admin_create)
    app.router.add_put('/api/admins/{name}/password', admin_password)
    app.router.add_delete('/api/admins/{name}', admin_delete)
    app.router.add_post('/api/tokens', api_token_create)
    app.router.add_delete('/api/tokens/{id:\\d+}', api_token_revoke)
    app.router.add_post('/api/nodes', node_save)
    app.router.add_put('/api/nodes/{id:\\d+}', node_save)
    app.router.add_post('/api/nodes/{id:\\d+}/credentials', node_secret)
    app.router.add_post('/api/clients', client_save)
    app.router.add_put('/api/clients/{id:\\d+}', client_save)
    app.router.add_post('/api/clients/{id:\\d+}/access', client_access)
    app.router.add_post('/api/clients/{id:\\d+}/rotate', rotate)
    app.router.add_post('/api/clients/{id:\\d+}/profile', client_profile)
    app.router.add_get('/api/clients/{id:\\d+}/telemetry', telemetry_history)
    app.router.add_get('/s/{token}.json', subscription)
    app.router.add_get('/agent/config', agent_config)
    app.router.add_post('/agent/status', agent_status)
    app.router.add_post('/agent/activity', agent_activity)
    app.router.add_get('/healthz', health)
    app.router.add_get('/', asset)
    app.router.add_get('/assets/{name}', asset)
    return app


def main():
    p = argparse.ArgumentParser()
    p.add_argument('command', choices=['init', 'reset-admin', 'serve', 'backup'])
    p.add_argument('--data', default=os.environ.get('SEVER_DATA', '/data'))
    p.add_argument('--username', default='admin')
    p.add_argument('--public-url', default=os.environ.get('PUBLIC_URL', ''))
    p.add_argument('--port', type=int, default=8090)
    p.add_argument('--development', action='store_true')
    p.add_argument('--output', help='New SQLite backup file (also preserve master.key separately)')
    args = p.parse_args()
    os.umask(0o077)
    store = Store(args.data, initialize=args.command == 'init')
    if args.command in ('init', 'reset-admin'):
        password = getpass.getpass('Пароль администратора (минимум 14 символов): ')
        if password != getpass.getpass('Повторите пароль: '):
            raise SystemExit('Пароли не совпадают')
        store.create_admin(args.username, password, reset=args.command == 'reset-admin')
        print('Администратор сохранён. Пароль не выводится и не записывается в историю.')
    elif args.command == 'backup':
        if not args.output or Path(args.output).exists():
            raise SystemExit('--output должен указывать новый файл')
        with store.db() as db:
            target = sqlite3.connect(args.output)
            try: db.backup(target)
            finally: target.close()
        os.chmod(args.output, 0o600)
        print('SQLite backup complete; preserve master.key with the backup.')
    else:
        web.run_app(make_app(store, args.public_url, args.development), host='127.0.0.1', port=args.port, access_log=None)

if __name__ == '__main__':
    main()
