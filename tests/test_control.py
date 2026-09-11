import asyncio
import hashlib
import json
from pathlib import Path
import secrets
import tempfile
import time
import unittest
import aiohttp
from aiohttp import web
from control.app import make_app
from control.store import Store, digest, password_ok
from sever.client import open_session
from sever.managed import Controller
from sever.server import Gateway, tls_context
from sever.webtransport import make_app as node_app
from sever.wire import Kind, encode, read_frame
import test_protocol as legacy


class ControlTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        legacy.TLSTests.setUpClass.__func__(cls)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    async def asyncSetUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.store = Store(self.folder.name, initialize=True)
        self.password = 'Strong test password 123!'
        self.store.create_admin('admin', self.password)
        self.origin = 'http://panel.example.test'
        self.runner = web.AppRunner(make_app(self.store, self.origin, development=True), access_log=None)
        await self.runner.setup()
        await web.TCPSite(self.runner, '127.0.0.1', 0).start()
        self.base = 'http://127.0.0.1:' + str(self.runner.addresses[0][1])
        self.http = aiohttp.ClientSession(cookie_jar=aiohttp.CookieJar(unsafe=True), headers={'Origin': self.origin})
        response = await self.http.post(self.base + '/api/login', json={'username': 'admin', 'password': self.password})
        self.assertEqual(response.status, 200)
        self.csrf = (await response.json())['csrf']
        self.http.headers['X-CSRF-Token'] = self.csrf

    async def asyncTearDown(self):
        await self.http.close()
        await self.runner.cleanup()
        self.folder.cleanup()

    async def request(self, method, path, data=None, status=200):
        async with self.http.request(method, self.base + path, json=data) as response:
            value = await response.json()
            self.assertEqual(response.status, status, value)
            return value

    async def setup_client(self, host='vpn.example.test', port=443):
        node = await self.request('POST', '/api/nodes', {'name': 'Node', 'host': host, 'port': port})
        node_id = node['id']
        credential = await self.request('POST', f'/api/nodes/{node_id}/credentials', {})
        client = await self.request('POST', '/api/clients', {'name': 'Phone', 'node_ids': [node_id]})
        return node_id, credential['node_token'], client['id']

    async def test_auth_csrf_origin_logout_and_no_secret_in_state(self):
        await self.setup_client()
        state = await self.request('GET', '/api/state')
        encoded = json.dumps(state)
        self.assertNotIn('token', encoded)
        self.assertNotIn('subscription', encoded)
        self.assertNotIn('password', encoded)
        async with self.http.post(self.base + '/api/nodes', json={'name':'bad'}, headers={'X-CSRF-Token':'bad'}) as response:
            self.assertEqual(response.status, 403)
        async with self.http.post(self.base + '/api/nodes', json={'name':'bad'}, headers={'Origin':'https://evil.test'}) as response:
            self.assertEqual(response.status, 403)
        await self.request('POST', '/api/logout', {})
        await self.request('GET', '/api/state', status=401)
        async with self.http.get(self.base + '/') as response:
            self.assertEqual(response.status, 200)
            self.assertIn('Content-Security-Policy', response.headers)
            self.assertIn('Cache-Control', response.headers)
            self.assertIn('VISION', await response.text())

    async def test_subscription_update_preserves_keys_and_revocation_rotation(self):
        node_id, token, client_id = await self.setup_client()
        original = await self.request('POST', f'/api/clients/{client_id}/profile', {})
        path = original['subscription_url'].split(self.origin)[1]
        await self.request('PUT', f'/api/nodes/{node_id}', {'name':'Changed','host':'new.example.test','port':8443})
        updated = await self.request('GET', path)
        self.assertEqual(updated['token'], original['token'])
        self.assertEqual(updated['subscription_url'], original['subscription_url'])
        self.assertEqual(updated['endpoints'][0]['port'], 8443)
        await self.request('POST', f'/api/clients/{client_id}/access', {'enabled': False})
        await self.request('GET', path, status=410)
        self.assertFalse(self.store.active_users(node_id))
        await self.request('POST', f'/api/clients/{client_id}/access', {'enabled': True})
        await self.request('POST', f'/api/clients/{client_id}/rotate', {})
        await self.request('GET', path, status=404)
        rotated = await self.request('POST', f'/api/clients/{client_id}/profile', {})
        self.assertNotEqual(rotated['token'], original['token'])
        self.assertNotEqual(rotated['subscription_url'], original['subscription_url'])

    async def test_agent_isolation_expiry_and_disabled_node(self):
        first, token, client_id = await self.setup_client()
        second = await self.request('POST','/api/nodes',{'name':'Second','host':'second.test','port':443})
        other = await self.request('POST',f'/api/nodes/{second["id"]}/credentials',{})
        async with aiohttp.ClientSession() as agent:
            async with agent.get(self.base + '/agent/config') as response:
                self.assertEqual(response.status, 401)
            async with agent.get(self.base + '/agent/config',headers={'Authorization':'Bearer '+other['node_token']}) as response:
                self.assertFalse((await response.json())['users'])
            async with agent.get(self.base + '/agent/config',headers={'Authorization':'Bearer '+token}) as response:
                self.assertEqual(len((await response.json())['users']),1)
        await self.request('PUT', f'/api/clients/{client_id}', {'name':'Expired','node_ids':[first],'expires':'2020-01-01T00:00:00Z'})
        self.assertFalse(self.store.active_users(first))
        await self.request('PUT', f'/api/clients/{client_id}', {'name':'Active','node_ids':[first]})
        await self.request('PUT', f'/api/nodes/{first}', {'name':'Off','host':'vpn.test','port':443,'enabled':False})
        self.assertFalse(self.store.active_users(first))

    async def test_failed_assignment_rolls_back_and_secrets_encrypted(self):
        node_id, token, client_id = await self.setup_client()
        await self.request('PUT',f'/api/clients/{client_id}',{'name':'Broken','node_ids':[9999]}, status=400)
        profile = await self.request('POST',f'/api/clients/{client_id}/profile',{})
        self.assertEqual(profile['name'],'Phone')
        with self.store.db() as db:
            row=db.execute('SELECT * FROM clients WHERE id=?',(client_id,)).fetchone()
            self.assertNotIn(profile['token'].encode(), row['token_enc'])
            self.assertEqual(row['token_hash'], digest(profile['token']))
            self.assertTrue(password_ok(self.password,db.execute('SELECT password FROM admins').fetchone()[0]))
        self.assertEqual((Path(self.folder.name)/'master.key').stat().st_mode & 0o777,0o600)

    async def test_control_to_real_wss_and_active_disconnect(self):
        tun=legacy.MemoryTun();gateway=Gateway(tun,{})
        node_runner=web.AppRunner(node_app(gateway),access_log=None,shutdown_timeout=2)
        await node_runner.setup()
        await web.TCPSite(node_runner,'127.0.0.1',0,ssl_context=tls_context(self.cert,self.key)).start()
        node_id,token,client_id=await self.setup_client('localhost',node_runner.addresses[0][1])
        controller=Controller(gateway,self.base,token,allow_http=True)
        pump=asyncio.create_task(gateway.tun_loop())
        writer=None
        try:
            async with aiohttp.ClientSession(headers={'Authorization':'Bearer '+token}) as agent:
                await controller.sync_once(agent)
                p=await self.request('POST',f'/api/clients/{client_id}/profile',{})
                reader,writer,config=await open_session(p,self.cert)
                packet=legacy.packet(src=config['ip'])
                writer.write(encode(Kind.PACKET,packet));await writer.drain()
                self.assertEqual(await asyncio.wait_for(tun.outgoing.get(),2),packet)
                await controller.sync_once(agent)
                snapshot=await self.request('GET','/api/state')
                self.assertEqual(snapshot['nodes'][0]['online'],1)
                await self.request('POST',f'/api/clients/{client_id}/access',{'enabled':False})
                await controller.sync_once(agent)
                with self.assertRaises(asyncio.IncompleteReadError):
                    await read_frame(reader,2)
                self.assertFalse(gateway.users)
                await asyncio.sleep(.01)
                self.assertFalse(gateway.peers)
                self.assertFalse(gateway.peer_writers)
        finally:
            if writer: writer.close();await writer.wait_closed()
            pump.cancel();await asyncio.gather(pump,return_exceptions=True)
            await node_runner.cleanup()

    async def test_control_lease_expires_and_invalid_config_not_applied(self):
        gateway=Gateway(legacy.MemoryTun(),{'test':{'ip':'10.77.0.2','token_sha256':'0'*64}})
        token=secrets.token_urlsafe(32)
        c=Controller(gateway,self.base,token,allow_http=True)
        c.last_success=100
        c.expire(159);self.assertTrue(gateway.users)
        c.expire(160);self.assertFalse(gateway.users)
        with self.assertRaises(ValueError):Controller(gateway,'http://remote.test',token)
        await self.request('POST','/api/nodes',{'name':'x','host':'https://bad.test/path','port':True},status=400)
        await self.request('POST','/api/clients',{'name':'x','node_ids':[]},status=400)

    async def test_client_telemetry_auth_snapshot_and_history(self):
        _, _, client_id = await self.setup_client()
        profile = await self.request('POST', f'/api/clients/{client_id}/profile', {})
        sample = {
            'platform': 'android', 'engine': 'vision', 'transport': 'wss', 'state': 'connected',
            'endpoint': 'vpn.example.test:443', 'network': 'wifi', 'rx': 123456, 'tx': 654321,
            'rtt_ms': 42, 'reconnects': 2, 'app_version': '0.2.0-dev1'
        }
        async with self.http.post(self.base + '/api/client-telemetry', json=sample,
                                  auth=aiohttp.BasicAuth(profile['user'], profile['token'])) as response:
            self.assertEqual(response.status, 200, await response.text())
        async with self.http.post(self.base + '/api/client-telemetry', json=sample,
                                  auth=aiohttp.BasicAuth(profile['user'], 'wrong-token')) as response:
            self.assertEqual(response.status, 401)
        state = await self.request('GET', '/api/state')
        telemetry = state['clients'][0]['telemetry']
        self.assertEqual(telemetry['engine'], 'vision')
        self.assertEqual(telemetry['network'], 'wifi')
        self.assertEqual(telemetry['rtt_ms'], 42)
        self.assertNotIn('token', json.dumps(telemetry))
        history = await self.request('GET', f'/api/clients/{client_id}/telemetry?limit=10')
        self.assertEqual(len(history['samples']), 1)
        self.assertEqual(history['samples'][0]['rx'], 123456)
        await self.request('POST', f'/api/clients/{client_id}/access', {'enabled': False})
        async with self.http.post(self.base + '/api/client-telemetry', json=sample,
                                  auth=aiohttp.BasicAuth(profile['user'], profile['token'])) as response:
            self.assertEqual(response.status, 401)

    async def test_login_throttle_and_password_reset_invalidates_sessions(self):
        self.store.create_admin('admin','New strong password 999!',reset=True)
        await self.request('GET','/api/state',status=401)
        for _ in range(4):
            await self.request('POST','/api/login',{'username':'admin','password':'incorrect password'},status=401)
        await self.request('POST','/api/login',{'username':'admin','password':'incorrect password'},status=429)


    async def test_policy_engines_routes_dns_are_managed_and_embedded(self):
        _, _, client_id = await self.setup_client()
        await self.request('PUT', '/api/settings', {
            'dns_mode':'custom','custom_dns':'9.9.9.9','default_mtu':1380,
            'allow_local_network':True,'reconnect_mode':'automatic','audit_retention_days':30,
            'telemetry_retention_days':14,'preferred_engine':'amneziawg','kill_switch':True,
        })
        await self.request('PUT', '/api/engines/openvpn', {'enabled':False,'priority':80,'mode':'external'})
        route = await self.request('POST', '/api/routes', {
            'name':'Corp','kind':'cidr','target':'10.0.0.7/8','action':'bypass','platform':'all','priority':5,'enabled':True
        })
        dns = await self.request('POST', '/api/dns-rules', {'pattern':'*.ads.example','action':'block','enabled':True})
        state = await self.request('GET','/api/state')
        self.assertEqual(state['settings']['preferred_engine'],'amneziawg')
        self.assertEqual(state['routes'][0]['target'],'10.0.0.0/8')
        self.assertEqual(state['dns_rules'][0]['pattern'],'*.ads.example')
        profile = await self.request('POST', f'/api/clients/{client_id}/profile', {})
        self.assertEqual(profile['policy']['preferred_engine'],'amneziawg')
        self.assertEqual(profile['policy']['dns']['custom'],'9.9.9.9')
        self.assertEqual(profile['policy']['mtu'],1380)
        self.assertFalse(next(e for e in profile['policy']['engines'] if e['engine']=='openvpn')['enabled'])
        await self.request('DELETE', f'/api/routes/{route["id"]}')
        await self.request('DELETE', f'/api/dns-rules/{dns["id"]}')

    async def test_owner_roles_api_tokens_and_viewer_read_only(self):
        await self.request('POST','/api/admins',{'name':'viewer','password':'Viewer password 123!','role':'viewer'})
        state=await self.request('GET','/api/state')
        self.assertTrue(any(a['name']=='viewer' for a in state['admins']))
        token = await self.request('POST','/api/tokens',{'name':'automation','scope':'write'})
        self.assertTrue(token['token'].startswith('vapi_'))
        async with aiohttp.ClientSession(headers={'Authorization':'Bearer '+token['token']}) as client:
            async with client.get(self.base+'/api/state') as response:
                self.assertEqual(response.status,200)
                api_state=await response.json()
                self.assertEqual(api_state['me']['role'],'admin')
            async with client.put(self.base+'/api/settings',json={'dns_mode':'node','custom_dns':'1.1.1.1','default_mtu':1280,'allow_local_network':False,'reconnect_mode':'automatic','audit_retention_days':30,'telemetry_retention_days':7,'preferred_engine':'auto','kill_switch':True}) as response:
                self.assertEqual(response.status,200,await response.text())
        viewer = aiohttp.ClientSession(cookie_jar=aiohttp.CookieJar(unsafe=True),headers={'Origin':self.origin})
        try:
            async with viewer.post(self.base+'/api/login',json={'username':'viewer','password':'Viewer password 123!'}) as response:
                self.assertEqual(response.status,200)
                vcsrf=(await response.json())['csrf']
            async with viewer.get(self.base+'/api/state') as response:
                self.assertEqual(response.status,200)
                vstate=await response.json()
                self.assertEqual(vstate['me']['role'],'viewer')
                self.assertEqual(vstate['admins'],[])
            async with viewer.post(self.base+'/api/nodes',json={'name':'No','host':'no.example','port':443},headers={'X-CSRF-Token':vcsrf}) as response:
                self.assertEqual(response.status,403)
        finally:
            await viewer.close()

    async def test_site_activity_agent_ingest_query_and_clear(self):
        node_id, token, client_id = await self.setup_client()
        profile = await self.request('POST', f'/api/clients/{client_id}/profile', {})
        user = profile['user']
        async with aiohttp.ClientSession(headers={'Authorization':'Bearer '+token}) as agent:
            async with agent.get(self.base + '/agent/config') as response:
                self.assertEqual(response.status, 200)
                config = await response.json()
                self.assertTrue(config['activity']['enabled'])
                self.assertTrue(config['activity']['log_ips'])
            now = time.time()
            payload = {'events': [
                {'user':user,'domain':'example.com','dest_ip':'','protocol':'dns',
                 'first_seen':now-2,'last_seen':now-2,'queries':1,'connections':0,'tx':0,'rx':0},
                {'user':user,'domain':'example.com','dest_ip':'93.184.216.34','protocol':'tcp',
                 'first_seen':now-1,'last_seen':now,'queries':0,'connections':2,'tx':1234,'rx':5678},
            ]}
            async with agent.post(self.base + '/agent/activity', json=payload) as response:
                self.assertEqual(response.status, 200, await response.text())
                self.assertEqual((await response.json())['saved'], 2)
        activity = await self.request('GET', f'/api/activity?client_id={client_id}&hours=24')
        self.assertEqual(activity['summary']['domains'], 1)
        self.assertEqual(len(activity['items']), 1)
        self.assertEqual(activity['items'][0]['domain'], 'example.com')
        self.assertEqual(activity['items'][0]['queries'], 1)
        self.assertEqual(activity['items'][0]['connections'], 2)
        self.assertEqual(activity['items'][0]['tx'], 1234)
        self.assertEqual(activity['items'][0]['rx'], 5678)
        cleared = await self.request('DELETE', '/api/activity', {'client_id': client_id})
        self.assertEqual(cleared['deleted'], 2)
        activity = await self.request('GET', f'/api/activity?client_id={client_id}&hours=24')
        self.assertEqual(activity['items'], [])

    async def test_backup_create_download_delete(self):
        await self.setup_client()
        made=await self.request('POST','/api/backups',{})
        self.assertTrue(made['name'].startswith('vision-control-'))
        state=await self.request('GET','/api/state')
        self.assertTrue(any(b['name']==made['name'] for b in state['backups']))
        async with self.http.get(self.base+'/api/backups/'+made['name']) as response:
            self.assertEqual(response.status,200)
            blob=await response.read()
            self.assertTrue(blob.startswith(b'PK'))
            self.assertGreater(len(blob),100)
        await self.request('DELETE','/api/backups/'+made['name'])
        state=await self.request('GET','/api/state')
        self.assertFalse(any(b['name']==made['name'] for b in state['backups']))

if __name__=='__main__':unittest.main()
