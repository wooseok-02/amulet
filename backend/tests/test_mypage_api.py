from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient
from postgrest.exceptions import APIError
from supabase import AuthApiError

from app.config import Settings, get_settings
from app.main import app
from app.supabase import get_supabase_client


client = TestClient(app)


@pytest.fixture
def supabase() -> Mock:
    database = Mock()
    users = {
        "token-a": ("user-a", "a@example.com"),
        "token-b": ("user-b", "b@example.com"),
    }
    profiles = {
        "user-a": {
            "name": "행운님", "birth_date": "2000-01-01", "calendar_type": "SOLAR",
            "is_leap_month": None, "birth_time": None, "gender": "FEMALE",
        },
        "user-b": {
            "name": "친구님", "birth_date": "1999-02-03", "calendar_type": "LUNAR",
            "is_leap_month": False, "birth_time": "13:30:00", "gender": "MALE",
        },
    }
    active = {"token-a", "token-b"}
    current_token = ""
    current_user = ""

    def get_user(token: str) -> SimpleNamespace:
        nonlocal current_token, current_user
        if token not in users:
            raise AuthApiError("invalid JWT", 401, "bad_jwt")
        current_token = token
        current_user, email = users[token]
        return SimpleNamespace(user=SimpleNamespace(id=current_user, email=email))

    def rpc(name: str) -> Mock:
        assert name == "is_current_auth_session_active"
        query = Mock()
        query.execute.return_value = SimpleNamespace(data=current_token in active)
        return query

    def table(name: str) -> Mock:
        assert name == "profiles"
        query = Mock()
        query.select.return_value = query
        def filter_user(column: str, value: str) -> Mock:
            assert (column, value) == ("id", current_user)
            return query
        query.eq.side_effect = filter_user
        query.maybe_single.return_value = query
        query.execute.side_effect = lambda: SimpleNamespace(data=profiles.get(current_user))
        return query

    database.auth.get_user.side_effect = get_user
    database.rpc.side_effect = rpc
    database.table.side_effect = table
    database.active_tokens = active
    database.profiles = profiles
    app.dependency_overrides[get_supabase_client] = lambda: database
    app.dependency_overrides[get_settings] = lambda: Settings(
        supabase_url="https://example.supabase.co",
        supabase_publishable_key="test-key",
        _env_file=None,
    )
    yield database
    app.dependency_overrides.clear()


def headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.parametrize(
    ("calendar", "leap", "expected"),
    [("SOLAR", None, None), ("LUNAR", False, False), ("LUNAR", True, True)],
)
def test_get_me_returns_calendar_and_birth_details(
    supabase: Mock, calendar: str, leap: bool | None, expected: bool | None,
) -> None:
    supabase.profiles["user-a"]["calendar_type"] = calendar
    supabase.profiles["user-a"]["is_leap_month"] = leap
    response = client.get("/api/v1/me", headers=headers("token-a"))

    assert response.status_code == 200
    assert response.json() == {
        "name": "행운님", "email": "a@example.com", "birthDate": "2000-01-01",
        "calendarType": calendar, "isLeapMonth": expected,
        "birthTime": None, "gender": "FEMALE",
    }


def test_get_me_keeps_users_separate_and_formats_time(supabase: Mock) -> None:
    first = client.get("/api/v1/me", headers=headers("token-a"))
    second = client.get("/api/v1/me", headers=headers("token-b"))

    assert first.json()["email"] == "a@example.com"
    assert second.json() == {
        "name": "친구님", "email": "b@example.com", "birthDate": "1999-02-03",
        "calendarType": "LUNAR", "isLeapMonth": False,
        "birthTime": "13:30", "gender": "MALE",
    }


def test_get_me_rejects_missing_invalid_and_signed_out_tokens(supabase: Mock) -> None:
    assert client.get("/api/v1/me").status_code == 401
    assert client.get("/api/v1/me", headers=headers("tampered")).status_code == 401
    assert client.post("/api/v1/auth/logout").status_code == 401
    supabase.active_tokens.remove("token-a")
    assert client.get("/api/v1/me", headers=headers("token-a")).status_code == 401
    assert client.get("/api/v1/me", headers=headers("token-b")).status_code == 200


def test_logout_revokes_only_current_session(supabase: Mock, monkeypatch: pytest.MonkeyPatch) -> None:
    transport = Mock()
    transport.post.side_effect = lambda *args, **kwargs: (
        supabase.active_tokens.remove("token-a") or SimpleNamespace(status_code=204)
    )
    context = Mock()
    context.__enter__ = Mock(return_value=transport)
    context.__exit__ = Mock(return_value=False)
    monkeypatch.setattr("app.mypage_api.httpx.Client", lambda **kwargs: context)

    response = client.post("/api/v1/auth/logout", headers=headers("token-a"))

    assert response.status_code == 204
    assert response.content == b""
    assert transport.post.call_args.args == ("https://example.supabase.co/auth/v1/logout",)
    assert transport.post.call_args.kwargs["params"] == {"scope": "local"}
    assert transport.post.call_args.kwargs["headers"]["Authorization"] == "Bearer token-a"
    assert transport.post.call_args.kwargs["headers"]["apikey"] == "test-key"
    assert "json" not in transport.post.call_args.kwargs
    assert client.get("/api/v1/me", headers=headers("token-a")).status_code == 401
    assert client.get("/api/v1/me", headers=headers("token-b")).status_code == 200


def test_logout_failure_does_not_return_success(supabase: Mock, monkeypatch: pytest.MonkeyPatch) -> None:
    transport = Mock()
    transport.post.return_value = SimpleNamespace(status_code=503)
    context = Mock()
    context.__enter__ = Mock(return_value=transport)
    context.__exit__ = Mock(return_value=False)
    monkeypatch.setattr("app.mypage_api.httpx.Client", lambda **kwargs: context)

    response = client.post("/api/v1/auth/logout", headers=headers("token-a"))

    assert response.status_code == 502
    assert "token-a" in supabase.active_tokens


def test_session_check_error_is_server_failure(supabase: Mock) -> None:
    supabase.rpc.side_effect = APIError({"message": "database unavailable", "code": "08006"})
    response = client.get("/api/v1/me", headers=headers("token-a"))
    assert response.status_code == 502
