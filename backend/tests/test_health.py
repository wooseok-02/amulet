from fastapi.testclient import TestClient

from app import main
from app.supabase import SupabaseConfigurationError


client = TestClient(main.app)


def test_health_check() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "environment": "development",
    }


def test_supabase_health_check(monkeypatch) -> None:
    monkeypatch.setattr(main, "check_supabase_connection", lambda: None)

    response = client.get("/health/supabase")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_supabase_health_check_returns_503_without_configuration(monkeypatch) -> None:
    def raise_configuration_error() -> None:
        raise SupabaseConfigurationError("missing configuration")

    monkeypatch.setattr(main, "check_supabase_connection", raise_configuration_error)

    response = client.get("/health/supabase")

    assert response.status_code == 503
    assert response.json() == {"detail": "Supabase connection unavailable."}

