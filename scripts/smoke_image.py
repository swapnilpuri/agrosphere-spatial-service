"""Smoke-test the built runtime image with isolated disposable local resources."""
import json
import http.client
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
import uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

prefix = 'agrosphere-spatial-smoke-' + uuid.uuid4().hex[:10]
network, database, application = prefix, prefix+'-db', prefix+'-app'
def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.STDOUT).strip()
class Oidc(BaseHTTPRequestHandler):
    def do_GET(self):
        document = {'keys': [json.loads((Path(__file__).resolve().parents[1]/'src/test/resources/oidc-public-jwk.json').read_text())]} if self.path == '/jwks' else {'issuer': issuer, 'jwks_uri': issuer+'/jwks', 'id_token_signing_alg_values_supported': ['RS256']}
        body=json.dumps(document).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(body)
    def log_message(self, *_):
        pass
server=ThreadingHTTPServer(('0.0.0.0',0),Oidc)
issuer='http://host.docker.internal:'+str(server.server_port)
threading.Thread(target=server.serve_forever,daemon=True).start()
try:
    docker('network','create',network)
    docker('run','-d','--name',database,'--network',network,'--network-alias','spatial-db',
           '-e','POSTGRES_DB=spatial_smoke','-e','POSTGRES_USER=spatial_smoke','-e','POSTGRES_PASSWORD=ephemeral-smoke-only','postgis/postgis:17-3.6')
    for attempt in range(60):
        try:
            docker('exec',database,'pg_isready','-h','127.0.0.1','-U','spatial_smoke','-d','spatial_smoke'); break
        except subprocess.CalledProcessError:
            time.sleep(1)
    else:
        raise RuntimeError('Disposable PostGIS did not become ready')
    docker('run','-d','--name',application,'--network',network,*(['--add-host','host.docker.internal:host-gateway'] if sys.platform!='win32' else []),'-p','127.0.0.1::8082',
           '-e','DB_URL=jdbc:postgresql://spatial-db:5432/spatial_smoke','-e','DB_USER=spatial_smoke','-e','DB_PASSWORD=ephemeral-smoke-only',
           '-e','RABBITMQ_USER=unused','-e','RABBITMQ_PASSWORD=unused','-e','MANAGEMENT_HEALTH_RABBIT_ENABLED=false',
           '-e','OIDC_ISSUER_URI='+issuer,'agrosphere-spatial-service:0.2.1')
    address=docker('port',application,'8082/tcp').splitlines()[0]
    base='http://'+address
    for attempt in range(90):
        try:
            with urllib.request.urlopen(base+'/actuator/health/readiness',timeout=2) as response:
                assert json.load(response)['status']=='UP'; break
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
            if docker('inspect','--format','{{.State.Running}}',application) != 'true':
                raise RuntimeError('Runtime exited: '+docker('logs',application)[-3000:])
            time.sleep(1)
    else:
        raise RuntimeError('Runtime image did not become ready')
    with urllib.request.urlopen(base+'/actuator/health/liveness',timeout=2) as response:
        assert response.status == 200
    try:
        urllib.request.urlopen(base+'/api/v1/spatial/estates/00000000-0000-0000-0000-000000000001/features',timeout=2)
        raise AssertionError('Business endpoint was public')
    except urllib.error.HTTPError as error:
        assert error.code==401
        assert error.headers.get_content_type()=='application/problem+json'
    user=docker('inspect','--format','{{.Config.User}}',application)
    assert user=='10001:10001', user
    version=docker('exec',application,'java','-version')
    assert '21.' in version, version
    print('PASS: built Java 21 image starts as 10001:10001, migrates PostGIS, passes readiness/liveness and denies unauthenticated business access')
finally:
    for container in (application,database):
        subprocess.run(['docker','rm','-f',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    subprocess.run(['docker','network','rm',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    server.shutdown(); server.server_close()
