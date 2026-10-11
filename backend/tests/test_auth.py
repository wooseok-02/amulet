from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient
from supabase import AuthApiError

from app.auth import (
    AUTH_SESSION_MISSING_MESSAGE,
    DUPLICATE_EMAIL_MESSAGE,
    INVALID_LOGIN_MESSAGE,
    INVALID_SESSION_MESSAGE,
)
from app.main import app
from app.supabase import get_supabase_client


client = TestClient(app)


def auth_response(
    *,
    user_id: str = "user-123",
    email: str = "user@example.com",
    access_token: str = "access-token",
    refresh_token: str = "refresh-token",
) -> SimpleNamespace:
    user = SimpleNamespace(id=user_id, email=email)
    session = SimpleNamespace(
        user=user,
        access_token=access_token,
        refresh_token=refresh_token,
        token_type="bearer",
        expires_in=3600,
        expires_at=1_800_000_000,
    )
    return SimpleNamespace(user=user, session=session)


@pytest.fixture
def mocked_auth() -> Mock:
    auth = Mock()
    app.dependency_overrides[get_supabase_client] = lambda: SimpleNamespace(auth=auth)
    yield auth
    app.dependency_overrides.clear()


def signup_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "email": " User@Example.com ",
        "password": "password1",
        "password_confirm": "password1",
        "name": " 홍길동 ",
        "birth_date": "1990-01-02",
        "calendar_type": "solar",
        "is_leap_month": False,
        "birth_time": "13:30",
        "gender": "male",
    }
    payload.update(overrides)
    return payload


# 회원가입 성공 시 세션을 반환하고 프로필 정보를 Supabase에 전달하는지 확인한다.
def test_signup_returns_session_and_sends_profile_metadata(mocked_auth: Mock) -> None:
    mocked_auth.sign_up.return_value = auth_response()

    response = client.post("/auth/signup", json=signup_payload())

    assert response.status_code == 201
    assert response.json() == {
        "user_id": "user-123",
        "email": "user@example.com",
        "access_token": "access-token",
        "refresh_token": "refresh-token",
        "token_type": "bearer",
        "expires_in": 3600,
        "expires_at": 1_800_000_000,
    }
    mocked_auth.sign_up.assert_called_once_with(
        {
            "email": "user@example.com",
            "password": "password1",
            "options": {
                "data": {
                    "name": "홍길동",
                    "birth_date": "1990-01-02",
                    "calendar_type": "SOLAR",
                    "is_leap_month": None,
                    "birth_time": "13:30",
                    "gender": "MALE",
                }
            },
        }
    )


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"email": "invalid"}, "올바른 이메일 형식이 아닙니다."),
        ({"password": "short1", "password_confirm": "short1"}, "비밀번호는 영문과 숫자를 포함해 8자 이상이어야 합니다."),
        ({"password_confirm": "different1"}, "비밀번호와 비밀번호 확인이 일치하지 않습니다."),
        ({"name": "김"}, "사용자 이름은 2자 이상 4자 이하여야 합니다."),
        ({"birth_date": "1899-12-31"}, "생년월일은 1900년부터 현재 연도까지 입력할 수 있습니다."),
        ({"calendar_type": "LUNAR", "is_leap_month": None}, "음력 생년월일은 윤달 여부가 필요합니다."),
    ],
)
# 잘못된 회원가입 입력을 각 검증 규칙에 맞는 메시지로 거부하는지 확인한다.
def test_signup_rejects_invalid_input(
    mocked_auth: Mock,
    overrides: dict[str, object],
    message: str,
) -> None:
    response = client.post("/auth/signup", json=signup_payload(**overrides))

    assert response.status_code == 422
    assert response.json() == {"detail": message}
    mocked_auth.sign_up.assert_not_called()


# 중복 이메일 오류를 HTTP 409 응답으로 변환하는지 확인한다.
def test_signup_maps_duplicate_email_to_conflict(mocked_auth: Mock) -> None:
    mocked_auth.sign_up.side_effect = AuthApiError(
        "User already registered",
        422,
        "user_already_exists",
    )

    response = client.post("/auth/signup", json=signup_payload())

    assert response.status_code == 409
    assert response.json() == {"detail": DUPLICATE_EMAIL_MESSAGE}


# 회원가입 직후 세션이 생성되지 않으면 오류를 반환하는지 확인한다.
def test_signup_requires_immediate_session(mocked_auth: Mock) -> None:
    mocked_auth.sign_up.return_value = SimpleNamespace(
        user=SimpleNamespace(id="user-123", email="user@example.com"),
        session=None,
    )

    response = client.post("/auth/signup", json=signup_payload())

    assert response.status_code == 502
    assert response.json() == {"detail": AUTH_SESSION_MISSING_MESSAGE}


# 정상 로그인 시 인증 세션을 반환하는지 확인한다.
def test_login_returns_session(mocked_auth: Mock) -> None:
    mocked_auth.sign_in_with_password.return_value = auth_response()

    response = client.post(
        "/auth/login",
        json={"email": " User@Example.com ", "password": "password1"},
    )

    assert response.status_code == 200
    assert response.json()["access_token"] == "access-token"
    mocked_auth.sign_in_with_password.assert_called_once_with(
        {"email": "user@example.com", "password": "password1"}
    )


# 로그인 실패 원인과 관계없이 동일한 오류 메시지를 반환하는지 확인한다.
def test_login_uses_one_message_for_all_auth_failures(mocked_auth: Mock) -> None:
    mocked_auth.sign_in_with_password.side_effect = AuthApiError(
        "Invalid login credentials",
        400,
        "invalid_credentials",
    )

    response = client.post(
        "/auth/login",
        json={"email": "user@example.com", "password": "wrong-password1"},
    )

    assert response.status_code == 401
    assert response.json() == {"detail": INVALID_LOGIN_MESSAGE}


# 유효한 갱신 토큰으로 새 인증 세션을 발급하는지 확인한다.
def test_refresh_returns_reissued_session(mocked_auth: Mock) -> None:
    mocked_auth.refresh_session.return_value = auth_response(
        access_token="new-access-token",
        refresh_token="new-refresh-token",
    )

    response = client.post(
        "/auth/refresh",
        json={"refresh_token": "old-refresh-token"},
    )

    assert response.status_code == 200
    assert response.json()["access_token"] == "new-access-token"
    assert response.json()["refresh_token"] == "new-refresh-token"
    mocked_auth.refresh_session.assert_called_once_with("old-refresh-token")


# 유효하지 않은 갱신 토큰을 HTTP 401로 거부하는지 확인한다.
def test_refresh_rejects_invalid_token(mocked_auth: Mock) -> None:
    mocked_auth.refresh_session.side_effect = AuthApiError(
        "Invalid Refresh Token",
        400,
        "refresh_token_not_found",
    )

    response = client.post(
        "/auth/refresh",
        json={"refresh_token": "invalid-token"},
    )

    assert response.status_code == 401
    assert response.json() == {"detail": INVALID_SESSION_MESSAGE}
