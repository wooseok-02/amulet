from fastapi.testclient import TestClient

from app import main
from app.supabase import SupabaseConfigurationError


client = TestClient(main.app)


# 기본 상태 확인 API가 정상 상태와 실행 환경을 반환하는지 확인한다.
def test_health_check() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "environment": "development",
    }


# Supabase 연결 성공 시 상태 확인 API가 정상 응답하는지 확인한다.
def test_supabase_health_check(monkeypatch) -> None:
    monkeypatch.setattr(main, "check_supabase_connection", lambda: None)

    response = client.get("/health/supabase")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


# Supabase 설정이 없으면 상태 확인 API가 HTTP 503을 반환하는지 확인한다.
def test_supabase_health_check_returns_503_without_configuration(monkeypatch) -> None:
    def raise_configuration_error() -> None:
        raise SupabaseConfigurationError("missing configuration")

    monkeypatch.setattr(main, "check_supabase_connection", raise_configuration_error)

    response = client.get("/health/supabase")

    assert response.status_code == 503
    assert response.json() == {"detail": "Supabase connection unavailable."}

