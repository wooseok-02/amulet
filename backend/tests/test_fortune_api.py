from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi.security import HTTPAuthorizationCredentials
from fastapi.testclient import TestClient
from supabase import AuthApiError

from app.fortune import ConcernInterpretation
from app.fortune_api import (
    AUTH_REQUIRED_MESSAGE,
    INVALID_ACCESS_TOKEN_MESSAGE,
    FortuneRequestContext,
    get_authenticated_supabase,
    get_fortune_request_context,
)
from app.main import app
from app.supabase import get_supabase_client
from tests.test_fortune import concern, fortune


client = TestClient(app)


@pytest.fixture
def fortune_context() -> tuple[Mock, FortuneRequestContext]:
    service = Mock()
    context = FortuneRequestContext(user_id="user-123", service=service)
    app.dependency_overrides[get_fortune_request_context] = lambda: context
    yield service, context
    app.dependency_overrides.clear()


def test_today_fortune_requires_bearer_token() -> None:
    response = client.get("/fortune/today")

    assert response.status_code == 401
    assert response.json() == {"detail": AUTH_REQUIRED_MESSAGE}


def test_today_fortune_rejects_invalid_access_token() -> None:
    supabase = Mock()
    supabase.auth.get_user.side_effect = AuthApiError(
        "invalid JWT",
        401,
        "bad_jwt",
    )
    app.dependency_overrides[get_supabase_client] = lambda: supabase

    response = client.get(
        "/fortune/today",
        headers={"Authorization": "Bearer invalid-token"},
    )

    app.dependency_overrides.clear()
    assert response.status_code == 401
    assert response.json() == {"detail": INVALID_ACCESS_TOKEN_MESSAGE}


def test_today_fortune_returns_service_result(
    fortune_context: tuple[Mock, FortuneRequestContext],
) -> None:
    service, _ = fortune_context
    service.get_daily_fortune.return_value = fortune()

    response = client.get("/fortune/today")

    assert response.status_code == 200
    assert response.json()["headline"] == "흐름이 천천히 열리는 날"
    assert response.json()["total_score"] == 78
    service.get_daily_fortune.assert_called_once_with("user-123")


def test_interpret_concern_returns_two_lines(
    fortune_context: tuple[Mock, FortuneRequestContext],
) -> None:
    service, _ = fortune_context
    result = ConcernInterpretation(
        day_message="진심 있게 답하는 태도가 빛나는 날이에요.",
        action_advice="첫 문장을 소리 내어 한 번 연습해보세요.",
    )
    service.interpret_concern.return_value = result

    response = client.post(
        "/fortune/interpret",
        json=concern().model_dump(mode="json"),
    )

    assert response.status_code == 200
    assert response.json() == result.model_dump(mode="json")
    service.interpret_concern.assert_called_once_with("user-123", concern())


def test_authenticated_request_sets_token_on_postgrest() -> None:
    supabase = Mock()
    supabase.auth.get_user.return_value = SimpleNamespace(
        user=SimpleNamespace(id="user-123")
    )
    credentials = HTTPAuthorizationCredentials(
        scheme="Bearer",
        credentials="access-token",
    )

    result = get_authenticated_supabase(credentials, supabase)

    assert result.user_id == "user-123"
    supabase.auth.get_user.assert_called_once_with("access-token")
    supabase.postgrest.auth.assert_called_once_with("access-token")
