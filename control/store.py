import base64
import hashlib
import hmac
import ipaddress
import json
import os
import re
from pathlib import Path
import secrets
import sqlite3
import tempfile
import time
import zipfile
from contextlib import contextmanager
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def password_hash(password, salt=None):
    salt = salt or secrets.token_bytes(16)
    key = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1, dklen=32)
    return base64.b64encode(salt + key).decode()


def password_ok(password, stored):
    try:
        salt = base64.b64decode(stored, validate=True)[:16]
        return hmac.compare_digest(password_hash(password, salt), stored)
    except (ValueError, TypeError):
        return False


class Store:
    def __init__(self, directory, initialize=False):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.directory / 'control.sqlite3'
        self.backup_dir = self.directory / 'backups'
        self.backup_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        key_path = self.directory / 'master.key'
        if not key_path.exists():
            if self.path.exists() or not initialize:
                raise ValueError('Нет master.key; восстановите ключ вместе с базой или выполните init')
            fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as f:
                f.write(secrets.token_bytes(32))
        self.key_path = key_path
        self.cipher = AESGCM(key_path.read_bytes())
        with self.db() as db:
            db.executescript('''
            CREATE TABLE IF NOT EXISTS admins(name TEXT PRIMARY KEY, password TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY, csrf TEXT NOT NULL, expires REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS nodes(id INTEGER PRIMARY KEY, name TEXT NOT NULL,
              host TEXT NOT NULL, port INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
              token_hash TEXT NOT NULL UNIQUE, token_enc BLOB NOT NULL,
              last_seen REAL, online INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS clients(id INTEGER PRIMARY KEY, name TEXT NOT NULL,
              user TEXT NOT NULL UNIQUE, ip TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1,
              expires REAL, token_hash TEXT NOT NULL, token_enc BLOB NOT NULL,
              subscription_hash TEXT NOT NULL UNIQUE, subscription_enc BLOB NOT NULL, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS assignments(client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
              node_id INTEGER NOT NULL REFERENCES nodes(id), PRIMARY KEY(client_id,node_id));
            CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, at REAL NOT NULL, action TEXT NOT NULL, subject TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS client_telemetry(
              user TEXT PRIMARY KEY, at REAL NOT NULL, platform TEXT NOT NULL, engine TEXT NOT NULL,
              transport TEXT NOT NULL, state TEXT NOT NULL, endpoint TEXT NOT NULL, network TEXT NOT NULL,
              rx INTEGER NOT NULL, tx INTEGER NOT NULL, rtt_ms INTEGER, reconnects INTEGER NOT NULL,
              app_version TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS telemetry_samples(
              id INTEGER PRIMARY KEY, user TEXT NOT NULL, at REAL NOT NULL, engine TEXT NOT NULL,
              network TEXT NOT NULL, rx INTEGER NOT NULL, tx INTEGER NOT NULL, rtt_ms INTEGER,
              reconnects INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS telemetry_samples_user_at ON telemetry_samples(user,at DESC);
            CREATE TABLE IF NOT EXISTS engine_policies(
              engine TEXT PRIMARY KEY, enabled INTEGER NOT NULL, priority INTEGER NOT NULL,
              mode TEXT NOT NULL DEFAULT 'auto', config TEXT NOT NULL DEFAULT '{}');
            CREATE TABLE IF NOT EXISTS routing_rules(
              id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL,
              action TEXT NOT NULL, platform TEXT NOT NULL DEFAULT 'all', enabled INTEGER NOT NULL DEFAULT 1,
              priority INTEGER NOT NULL DEFAULT 100, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS dns_rules(
              id INTEGER PRIMARY KEY, pattern TEXT NOT NULL, action TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS api_tokens(
              id INTEGER PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
              scope TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
              created REAL NOT NULL, last_used REAL);
            CREATE TABLE IF NOT EXISTS site_activity(
              id INTEGER PRIMARY KEY,
              node_id INTEGER NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
              user TEXT NOT NULL,
              domain TEXT NOT NULL DEFAULT '',
              dest_ip TEXT NOT NULL DEFAULT '',
              protocol TEXT NOT NULL DEFAULT '',
              first_seen REAL NOT NULL,
              last_seen REAL NOT NULL,
              queries INTEGER NOT NULL DEFAULT 0,
              connections INTEGER NOT NULL DEFAULT 0,
              tx INTEGER NOT NULL DEFAULT 0,
              rx INTEGER NOT NULL DEFAULT 0,
              UNIQUE(node_id,user,domain,dest_ip));
            CREATE INDEX IF NOT EXISTS site_activity_user_last ON site_activity(user,last_seen DESC);
            CREATE INDEX IF NOT EXISTS site_activity_last ON site_activity(last_seen DESC);
            ''')
            self._ensure_column(db, 'admins', 'role', "TEXT NOT NULL DEFAULT 'owner'")
            self._ensure_column(db, 'admins', 'created', 'REAL')
            self._ensure_column(db, 'sessions', 'user', 'TEXT')
            db.execute("UPDATE admins SET role='owner' WHERE role IS NULL OR role='' ")
            db.execute('UPDATE admins SET created=COALESCE(created,?)', (time.time(),))
            db.execute('DELETE FROM sessions WHERE user IS NULL')
            db.execute('PRAGMA user_version=5')
        os.chmod(self.path, 0o600)
        defaults = {
            'dns_mode': 'node', 'custom_dns': '1.1.1.1', 'default_mtu': '1280',
            'allow_local_network': '0', 'reconnect_mode': 'automatic',
            'audit_retention_days': '30', 'telemetry_retention_days': '7',
            'preferred_engine': 'auto', 'kill_switch': '1', 'future_multi_protocol': '1',
            'activity_enabled': '1', 'activity_retention_days': '30', 'activity_log_ips': '1',
        }
        engines = [('vision',1,10,'auto','{}'),('wireguard',1,20,'auto','{}'),
                   ('amneziawg',1,30,'auto','{}'),('openvpn',1,40,'external','{}'),('xray',1,50,'pack','{}')]
        with self.db() as db:
            db.executemany('INSERT OR IGNORE INTO settings(key,value) VALUES(?,?)', defaults.items())
            db.executemany('INSERT OR IGNORE INTO engine_policies(engine,enabled,priority,mode,config) VALUES(?,?,?,?,?)', engines)

    def _ensure_column(self, db, table, column, ddl):
        cols = {r[1] for r in db.execute(f'PRAGMA table_info({table})')}
        if column not in cols:
            db.execute(f'ALTER TABLE {table} ADD COLUMN {column} {ddl}')

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('PRAGMA journal_mode=WAL')
        try:
            with db:
                yield db
        finally:
            db.close()

    def seal(self, value):
        nonce = secrets.token_bytes(12)
        return nonce + self.cipher.encrypt(nonce, value.encode(), b'sever-control-v1')

    def unseal(self, value):
        return self.cipher.decrypt(value[:12], value[12:], b'sever-control-v1').decode()

    def audit(self, db, action, subject):
        now = time.time()
        db.execute('INSERT INTO events(at,action,subject) VALUES(?,?,?)', (now, action, str(subject)))
        row = db.execute("SELECT value FROM settings WHERE key='audit_retention_days'").fetchone()
        days = max(1, min(int(row[0]) if row else 30, 3650))
        db.execute('DELETE FROM events WHERE at<?', (now - days * 86400,))
        db.execute('DELETE FROM events WHERE id <= (SELECT COALESCE(MAX(id),0)-10000 FROM events)')

    def create_admin(self, name, password, reset=False, role='owner'):
        if not name or len(name) > 64 or len(password) < 14 or len(password) > 256:
            raise ValueError('Логин 1–64 символа, пароль 14–256 символов')
        if role not in ('owner','admin','viewer'):
            raise ValueError('Некорректная роль')
        encoded = password_hash(password)
        with self.db() as db:
            if db.execute('SELECT 1 FROM admins').fetchone() and not reset:
                raise ValueError('Администратор уже создан; для смены используйте reset-admin')
            if reset:
                db.execute('DELETE FROM admins')
            db.execute('INSERT OR REPLACE INTO admins(name,password,role,created) VALUES(?,?,?,?)', (name, encoded, role, time.time()))
            db.execute('DELETE FROM sessions')
            self.audit(db, 'admin.password_changed' if reset else 'admin.created', name)

    def admins(self):
        with self.db() as db:
            return [dict(r) for r in db.execute('SELECT name,role,created FROM admins ORDER BY created,name')]

    def add_admin(self, name, password, role):
        if not isinstance(name,str) or not name.strip() or len(name.strip()) > 64:
            raise ValueError('Логин 1–64 символа')
        if not isinstance(password,str) or not 14 <= len(password) <= 256:
            raise ValueError('Пароль 14–256 символов')
        if role not in ('owner','admin','viewer'):
            raise ValueError('Некорректная роль')
        with self.db() as db:
            db.execute('INSERT INTO admins(name,password,role,created) VALUES(?,?,?,?)', (name.strip(), password_hash(password), role, time.time()))
            self.audit(db, 'admin.created', name.strip())

    def set_admin_password(self, name, password):
        if not isinstance(password,str) or not 14 <= len(password) <= 256:
            raise ValueError('Пароль 14–256 символов')
        with self.db() as db:
            cur = db.execute('UPDATE admins SET password=? WHERE name=?', (password_hash(password), name))
            if not cur.rowcount:
                raise ValueError('Администратор не найден')
            db.execute('DELETE FROM sessions WHERE user=?', (name,))
            self.audit(db, 'admin.password_changed', name)

    def delete_admin(self, name, actor):
        if name == actor:
            raise ValueError('Нельзя удалить текущую учётную запись')
        with self.db() as db:
            row = db.execute('SELECT role FROM admins WHERE name=?', (name,)).fetchone()
            if not row:
                raise ValueError('Администратор не найден')
            if row['role'] == 'owner' and db.execute("SELECT COUNT(*) FROM admins WHERE role='owner'").fetchone()[0] <= 1:
                raise ValueError('Должен остаться хотя бы один владелец')
            db.execute('DELETE FROM admins WHERE name=?', (name,))
            db.execute('DELETE FROM sessions WHERE user=?', (name,))
            self.audit(db, 'admin.deleted', name)

    def get_settings(self):
        with self.db() as db:
            return {r['key']: r['value'] for r in db.execute('SELECT key,value FROM settings ORDER BY key')}

    def save_settings(self, values):
        allowed = {'dns_mode','custom_dns','default_mtu','allow_local_network','reconnect_mode','audit_retention_days',
                   'telemetry_retention_days','preferred_engine','kill_switch','future_multi_protocol',
                   'activity_enabled','activity_retention_days','activity_log_ips'}
        clean = {k: str(v) for k, v in values.items() if k in allowed}
        if not clean:
            raise ValueError('Нет поддерживаемых параметров')
        with self.db() as db:
            db.executemany('INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value', clean.items())
            self.audit(db, 'settings.saved', ','.join(sorted(clean)))
        return self.get_settings()

    def engines(self):
        with self.db() as db:
            return [dict(r) for r in db.execute('SELECT engine,enabled,priority,mode,config FROM engine_policies ORDER BY priority,engine')]

    def save_engine(self, engine, enabled, priority, mode):
        if engine not in {'vision','wireguard','amneziawg','openvpn','xray'}:
            raise ValueError('Неизвестный движок')
        if type(enabled) is not bool or type(priority) is not int or not 1 <= priority <= 999:
            raise ValueError('Некорректные параметры движка')
        if mode not in {'auto','external','pack','disabled'}:
            raise ValueError('Некорректный режим движка')
        with self.db() as db:
            db.execute('UPDATE engine_policies SET enabled=?,priority=?,mode=? WHERE engine=?', (int(enabled),priority,mode,engine))
            self.audit(db, 'engine.saved', engine)
        return self.engines()

    def routes(self):
        with self.db() as db:
            return [dict(r) for r in db.execute('SELECT * FROM routing_rules ORDER BY priority,id')]

    def save_route(self, data, rule_id=None):
        name = str(data.get('name','')).strip(); kind = data.get('kind'); target = str(data.get('target','')).strip()
        action = data.get('action'); platform = data.get('platform','all'); enabled = data.get('enabled',True); priority = data.get('priority',100)
        if not name or len(name)>80 or not target or len(target)>512:
            raise ValueError('Название/цель правила заполнены некорректно')
        if kind not in ('domain','cidr','app') or action not in ('vpn','bypass','block') or platform not in ('all','android','ios'):
            raise ValueError('Некорректный тип правила')
        if type(enabled) is not bool or type(priority) is not int or not 1 <= priority <= 9999:
            raise ValueError('Некорректный приоритет')
        if kind == 'cidr':
            import ipaddress
            try: target = str(ipaddress.ip_network(target, strict=False))
            except ValueError: raise ValueError('Некорректный IP/CIDR')
        with self.db() as db:
            if rule_id:
                cur=db.execute('UPDATE routing_rules SET name=?,kind=?,target=?,action=?,platform=?,enabled=?,priority=? WHERE id=?',
                               (name,kind,target,action,platform,int(enabled),priority,rule_id))
                if not cur.rowcount: raise ValueError('Правило не найдено')
            else:
                cur=db.execute('INSERT INTO routing_rules(name,kind,target,action,platform,enabled,priority,created) VALUES(?,?,?,?,?,?,?,?)',
                               (name,kind,target,action,platform,int(enabled),priority,time.time())); rule_id=cur.lastrowid
            self.audit(db, 'route.saved', rule_id)
        return rule_id

    def delete_route(self, rule_id):
        with self.db() as db:
            cur=db.execute('DELETE FROM routing_rules WHERE id=?',(rule_id,))
            if not cur.rowcount: raise ValueError('Правило не найдено')
            self.audit(db,'route.deleted',rule_id)

    def dns_rules(self):
        with self.db() as db:
            return [dict(r) for r in db.execute('SELECT * FROM dns_rules ORDER BY id')]

    def save_dns_rule(self, data, rule_id=None):
        pattern=str(data.get('pattern','')).strip().lower(); action=data.get('action'); enabled=data.get('enabled',True)
        if not pattern or len(pattern)>253 or action not in ('allow','block','vpn','bypass') or type(enabled) is not bool:
            raise ValueError('Некорректное DNS-правило')
        if ' ' in pattern or '/' in pattern:
            raise ValueError('Укажите домен или шаблон домена')
        with self.db() as db:
            if rule_id:
                cur=db.execute('UPDATE dns_rules SET pattern=?,action=?,enabled=? WHERE id=?',(pattern,action,int(enabled),rule_id))
                if not cur.rowcount: raise ValueError('DNS-правило не найдено')
            else:
                cur=db.execute('INSERT INTO dns_rules(pattern,action,enabled,created) VALUES(?,?,?,?)',(pattern,action,int(enabled),time.time())); rule_id=cur.lastrowid
            self.audit(db,'dns_rule.saved',rule_id)
        return rule_id

    def delete_dns_rule(self, rule_id):
        with self.db() as db:
            cur=db.execute('DELETE FROM dns_rules WHERE id=?',(rule_id,))
            if not cur.rowcount: raise ValueError('DNS-правило не найдено')
            self.audit(db,'dns_rule.deleted',rule_id)

    def api_tokens(self):
        with self.db() as db:
            return [dict(r) for r in db.execute('SELECT id,name,scope,enabled,created,last_used FROM api_tokens ORDER BY id DESC')]

    def create_api_token(self, name, scope):
        name=str(name or '').strip()
        if not name or len(name)>80 or scope not in ('read','write'):
            raise ValueError('Некорректный API-токен')
        token='vapi_'+secrets.token_urlsafe(32)
        with self.db() as db:
            cur=db.execute('INSERT INTO api_tokens(name,token_hash,scope,enabled,created) VALUES(?,?,?,?,?)',(name,digest(token),scope,1,time.time()))
            self.audit(db,'api_token.created',cur.lastrowid)
        return token

    def revoke_api_token(self, token_id):
        with self.db() as db:
            cur=db.execute('UPDATE api_tokens SET enabled=0 WHERE id=?',(token_id,))
            if not cur.rowcount: raise ValueError('API-токен не найден')
            self.audit(db,'api_token.revoked',token_id)

    def api_auth(self, token):
        supplied=digest(token)
        with self.db() as db:
            row=db.execute('SELECT id,name,scope,enabled FROM api_tokens WHERE token_hash=?',(supplied,)).fetchone()
            if not row or not row['enabled']:
                hmac.compare_digest(supplied,'0'*64); return None
            db.execute('UPDATE api_tokens SET last_used=? WHERE id=?',(time.time(),row['id']))
            return dict(row)

    def create_backup(self):
        stamp=time.strftime('%Y%m%d-%H%M%S', time.gmtime())
        name=f'vision-control-{stamp}.zip'; out=self.backup_dir/name
        suffix=0
        while out.exists():
            suffix+=1; name=f'vision-control-{stamp}-{suffix}.zip'; out=self.backup_dir/name
        fd,tmp=tempfile.mkstemp(prefix='vision-control-',suffix='.sqlite3',dir=self.backup_dir); os.close(fd)
        try:
            source=sqlite3.connect(self.path); target=sqlite3.connect(tmp)
            try: source.backup(target)
            finally: target.close(); source.close()
            meta={'product':'VISION Control','version':'1.2.0','created':time.time(),'database':'control.sqlite3','master_key':'master.key'}
            with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
                z.write(tmp,'control.sqlite3'); z.write(self.key_path,'master.key'); z.writestr('backup.json',json.dumps(meta,indent=2))
            os.chmod(out,0o600)
            with self.db() as db: self.audit(db,'backup.created',name)
            return name
        finally:
            try: os.unlink(tmp)
            except FileNotFoundError: pass

    def backups(self):
        result=[]
        for p in sorted(self.backup_dir.glob('vision-control-*.zip'), reverse=True):
            st=p.stat(); result.append({'name':p.name,'size':st.st_size,'created':st.st_mtime})
        return result[:50]

    def backup_path(self, name):
        if not isinstance(name,str) or not name.startswith('vision-control-') or not name.endswith('.zip') or '/' in name or '\\' in name:
            raise ValueError('Некорректное имя резервной копии')
        p=self.backup_dir/name
        if not p.is_file(): raise ValueError('Резервная копия не найдена')
        return p

    def delete_backup(self, name):
        p=self.backup_path(name); p.unlink()
        with self.db() as db: self.audit(db,'backup.deleted',name)

    def policy_bundle(self):
        s=self.get_settings()
        return {
            'preferred_engine': s.get('preferred_engine','auto'),
            'kill_switch': s.get('kill_switch','1') == '1',
            'dns': {'mode':s.get('dns_mode','node'),'custom':s.get('custom_dns','1.1.1.1'),'rules':self.dns_rules()},
            'mtu': int(s.get('default_mtu','1280')), 'allow_local_network':s.get('allow_local_network','0')=='1',
            'reconnect_mode':s.get('reconnect_mode','automatic'), 'engines':self.engines(), 'routes':self.routes(),
        }

    def client_auth(self, user, token):
        if not isinstance(user, str) or not isinstance(token, str): return False
        supplied = digest(token)
        with self.db() as db:
            row = db.execute('SELECT token_hash,enabled,expires FROM clients WHERE user=?', (user,)).fetchone()
        if not row:
            hmac.compare_digest(supplied, '0' * 64); return False
        valid = hmac.compare_digest(supplied, row['token_hash'])
        active = bool(row['enabled']) and (row['expires'] is None or row['expires'] > time.time())
        return valid and active

    def save_client_telemetry(self, user, values):
        now = time.time()
        row = (user, now, values['platform'], values['engine'], values['transport'], values['state'],
               values['endpoint'], values['network'], values['rx'], values['tx'], values['rtt_ms'],
               values['reconnects'], values['app_version'])
        settings=self.get_settings(); days=max(1,min(int(settings.get('telemetry_retention_days','7')),365))
        with self.db() as db:
            db.execute("""INSERT INTO client_telemetry
              (user,at,platform,engine,transport,state,endpoint,network,rx,tx,rtt_ms,reconnects,app_version)
              VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
              ON CONFLICT(user) DO UPDATE SET at=excluded.at,platform=excluded.platform,engine=excluded.engine,
              transport=excluded.transport,state=excluded.state,endpoint=excluded.endpoint,network=excluded.network,
              rx=excluded.rx,tx=excluded.tx,rtt_ms=excluded.rtt_ms,reconnects=excluded.reconnects,
              app_version=excluded.app_version""", row)
            db.execute("""INSERT INTO telemetry_samples(user,at,engine,network,rx,tx,rtt_ms,reconnects)
              VALUES(?,?,?,?,?,?,?,?)""", (user, now, values['engine'], values['network'], values['rx'], values['tx'], values['rtt_ms'], values['reconnects']))
            db.execute('DELETE FROM telemetry_samples WHERE at<?', (now - days * 86400,))
            db.execute("""DELETE FROM telemetry_samples WHERE user=? AND id NOT IN
              (SELECT id FROM telemetry_samples WHERE user=? ORDER BY id DESC LIMIT 5000)""", (user, user))
        return now

    def telemetry_latest(self):
        with self.db() as db: rows = db.execute('SELECT * FROM client_telemetry ORDER BY at DESC').fetchall()
        return {r['user']: dict(r) for r in rows}

    def telemetry_history(self, user, limit=120):
        limit = max(1, min(int(limit), 1000))
        with self.db() as db:
            return [dict(r) for r in db.execute("""SELECT at,engine,network,rx,tx,rtt_ms,reconnects
              FROM telemetry_samples WHERE user=? ORDER BY at DESC LIMIT ?""", (user, limit))]

    def activity_config(self):
        settings = self.get_settings()
        return {
            'enabled': settings.get('activity_enabled', '1') == '1',
            'log_ips': settings.get('activity_log_ips', '1') == '1',
            'retention_days': max(1, min(int(settings.get('activity_retention_days', '30')), 365)),
        }

    def save_activity(self, node_id, events):
        if not isinstance(events, list) or len(events) > 500:
            raise ValueError('Некорректный пакет активности')
        config = self.activity_config()
        if not config['enabled']:
            return 0
        now = time.time()
        earliest = now - config['retention_days'] * 86400
        protocols = {'', 'dns', 'tcp', 'udp', 'mixed'}
        with self.db() as db:
            valid_users = {r[0] for r in db.execute('''SELECT c.user FROM clients c
              JOIN assignments a ON a.client_id=c.id WHERE a.node_id=?''', (node_id,))}
            saved = 0
            for item in events:
                if not isinstance(item, dict):
                    raise ValueError('Некорректная запись активности')
                user = item.get('user')
                if user not in valid_users:
                    continue
                domain = str(item.get('domain') or '').strip().lower().rstrip('.')
                if domain and (len(domain) > 253 or not re.fullmatch(r'[a-z0-9_.-]+', domain)):
                    raise ValueError('Некорректный домен активности')
                dest_ip = str(item.get('dest_ip') or '').strip()
                if dest_ip:
                    try:
                        address = ipaddress.ip_address(dest_ip)
                    except ValueError as exc:
                        raise ValueError('Некорректный IP активности') from exc
                    if address.version != 4 or not address.is_global:
                        dest_ip = ''
                if not domain and not dest_ip:
                    continue
                protocol = str(item.get('protocol') or '')
                if protocol not in protocols:
                    raise ValueError('Некорректный протокол активности')
                try:
                    first_seen = float(item.get('first_seen', now))
                    last_seen = float(item.get('last_seen', first_seen))
                except (TypeError, ValueError) as exc:
                    raise ValueError('Некорректное время активности') from exc
                first_seen = min(now + 300, max(earliest, first_seen))
                last_seen = min(now + 300, max(first_seen, last_seen))
                counters = []
                for name, maximum in (('queries', 1_000_000), ('connections', 1_000_000),
                                      ('tx', 1 << 50), ('rx', 1 << 50)):
                    value = item.get(name, 0)
                    if type(value) is not int or not 0 <= value <= maximum:
                        raise ValueError('Некорректный счётчик активности')
                    counters.append(value)
                queries, connections, tx, rx = counters
                db.execute('''INSERT INTO site_activity
                  (node_id,user,domain,dest_ip,protocol,first_seen,last_seen,queries,connections,tx,rx)
                  VALUES(?,?,?,?,?,?,?,?,?,?,?)
                  ON CONFLICT(node_id,user,domain,dest_ip) DO UPDATE SET
                    protocol=CASE
                      WHEN site_activity.protocol='' THEN excluded.protocol
                      WHEN excluded.protocol='' OR site_activity.protocol=excluded.protocol THEN site_activity.protocol
                      ELSE 'mixed' END,
                    first_seen=MIN(site_activity.first_seen,excluded.first_seen),
                    last_seen=MAX(site_activity.last_seen,excluded.last_seen),
                    queries=site_activity.queries+excluded.queries,
                    connections=site_activity.connections+excluded.connections,
                    tx=site_activity.tx+excluded.tx,
                    rx=site_activity.rx+excluded.rx''',
                  (node_id,user,domain,dest_ip,protocol,first_seen,last_seen,queries,connections,tx,rx))
                saved += 1
            db.execute('DELETE FROM site_activity WHERE last_seen<?', (earliest,))
            db.execute('''DELETE FROM site_activity WHERE id NOT IN
              (SELECT id FROM site_activity ORDER BY last_seen DESC LIMIT 200000)''')
        return saved

    def activity_summary(self, client_id=None, hours=24, limit=250):
        try:
            hours = max(1, min(int(hours), 24 * 365))
            limit = max(1, min(int(limit), 1000))
        except (TypeError, ValueError) as exc:
            raise ValueError('Некорректный период активности') from exc
        cutoff = time.time() - hours * 3600
        params = [cutoff]
        where = 'sa.last_seen>=?'
        if client_id is not None:
            try:
                client_id = int(client_id)
            except (TypeError, ValueError) as exc:
                raise ValueError('Некорректный клиент') from exc
            where += ' AND c.id=?'
            params.append(client_id)
        params.append(limit)
        sql = f'''SELECT c.id AS client_id,c.name AS client_name,c.user,
          sa.domain,
          CASE WHEN sa.domain='' THEN sa.dest_ip ELSE GROUP_CONCAT(DISTINCT NULLIF(sa.dest_ip,'')) END AS dest_ips,
          MIN(sa.first_seen) AS first_seen,MAX(sa.last_seen) AS last_seen,
          SUM(sa.queries) AS queries,SUM(sa.connections) AS connections,
          SUM(sa.tx) AS tx,SUM(sa.rx) AS rx,
          CASE WHEN COUNT(DISTINCT NULLIF(sa.protocol,''))>1 THEN 'mixed' ELSE MAX(sa.protocol) END AS protocol
          FROM site_activity sa JOIN clients c ON c.user=sa.user
          WHERE {where}
          GROUP BY c.id,c.name,c.user,
            CASE WHEN sa.domain='' THEN 'ip:'||sa.dest_ip ELSE 'domain:'||sa.domain END
          ORDER BY last_seen DESC LIMIT ?'''
        with self.db() as db:
            rows = [dict(r) for r in db.execute(sql, params)]
            total = db.execute(f'''SELECT COUNT(*) AS rows,COUNT(DISTINCT CASE WHEN sa.domain<>'' THEN sa.domain END) AS domains,
              COALESCE(SUM(sa.tx),0) AS tx,COALESCE(SUM(sa.rx),0) AS rx
              FROM site_activity sa JOIN clients c ON c.user=sa.user WHERE {where}''', params[:-1]).fetchone()
        return {'items': rows, 'summary': dict(total), 'hours': hours}

    def clear_activity(self, client_id=None):
        with self.db() as db:
            if client_id is None:
                count = db.execute('SELECT COUNT(*) FROM site_activity').fetchone()[0]
                db.execute('DELETE FROM site_activity')
                subject = 'all'
            else:
                row = db.execute('SELECT user FROM clients WHERE id=?', (int(client_id),)).fetchone()
                if not row:
                    raise ValueError('Клиент не найден')
                count = db.execute('SELECT COUNT(*) FROM site_activity WHERE user=?', (row['user'],)).fetchone()[0]
                db.execute('DELETE FROM site_activity WHERE user=?', (row['user'],))
                subject = row['user']
            self.audit(db, 'activity.cleared', subject)
        return count

    def active_users(self, node_id):
        with self.db() as db:
            rows = db.execute('''SELECT c.user,c.ip,c.token_hash FROM clients c
              JOIN assignments a ON a.client_id=c.id JOIN nodes n ON n.id=a.node_id
              WHERE n.id=? AND n.enabled=1 AND c.enabled=1 AND (c.expires IS NULL OR c.expires>?)''',
              (node_id, time.time())).fetchall()
        return {r['user']: {'ip': r['ip'], 'token_sha256': r['token_hash']} for r in rows}

    def profile(self, client_id, origin):
        with self.db() as db:
            c = db.execute('SELECT * FROM clients WHERE id=?', (client_id,)).fetchone()
            if not c or not c['enabled'] or (c['expires'] is not None and c['expires'] <= time.time()):
                raise ValueError('Доступ отключён или истёк')
            nodes = db.execute('''SELECT n.host,n.port FROM nodes n JOIN assignments a ON n.id=a.node_id
              WHERE a.client_id=? AND n.enabled=1 ORDER BY n.id''', (client_id,)).fetchall()
            if not nodes: raise ValueError('Нет включённых серверов')
            sub = self.unseal(c['subscription_enc'])
            return {'version': 2, 'protocol': 'sever1', 'transport': 'wss', 'name': c['name'],
                'user': c['user'], 'token': self.unseal(c['token_enc']),
                'endpoints': [{'host': n['host'], 'port': n['port'], 'path': '/api/session'} for n in nodes],
                'policy': self.policy_bundle(), 'subscription_url': f'{origin}/s/{sub}.json'}
