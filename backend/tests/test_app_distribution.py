from fastapi.testclient import TestClient
from app.config import Settings, get_settings
from app.main import app


def test_unconfigured_distribution_is_json_only():
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None)
    try:
        client = TestClient(app)
        assert client.get('/app/install').json() == {'apk_available': False, 'download_url': None}
        response = client.get('/share/' + 'a' * 43)
        assert response.headers['content-type'].startswith('application/json')
        assert response.json() == {'apk_available': False, 'download_url': None}
        assert client.get('/app/download').status_code == 503
        assert client.get('/.well-known/assetlinks.json').status_code == 503
        assert client.get('/share/bad').status_code == 404
    finally:
        app.dependency_overrides.clear()


def test_apk_file_delivery_and_domain_association(tmp_path):
    apk = tmp_path / 'release.apk'
    apk.write_bytes(b'PK\x03\x04test-apk')
    fingerprint = ':'.join(['AB'] * 32)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, amulet_apk_file_path=apk, android_package_name='com.example.fixture',
        android_sha256_cert_fingerprints=[fingerprint])
    try:
        client = TestClient(app)
        assert client.get('/app/install').json() == {
            'apk_available': True, 'download_url': 'http://testserver/app/download'}
        response = client.get('/app/download')
        assert response.content == apk.read_bytes()
        assert response.headers['content-type'] == 'application/vnd.android.package-archive'
        assert 'attachment' in response.headers['content-disposition']
        assert client.get('/.well-known/assetlinks.json').json()[0]['target'] == {
            'namespace': 'android_app', 'package_name': 'com.example.fixture',
            'sha256_cert_fingerprints': [fingerprint]}
    finally:
        app.dependency_overrides.clear()


def test_external_apk_redirect_uses_server_configuration():
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, amulet_apk_download_url='https://files.example.test/release.apk')
    try:
        response = TestClient(app).get('/app/download', follow_redirects=False)
        assert response.status_code == 307
        assert response.headers['location'] == 'https://files.example.test/release.apk'
    finally:
        app.dependency_overrides.clear()
