import re
from datetime import date, time
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, field_validator, model_validator
from supabase import AuthApiError, AuthError, Client
from supabase_auth.types import AuthResponse

from app.supabase import get_supabase_client


router = APIRouter(prefix="/auth", tags=["auth"])

INVALID_LOGIN_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다."
INVALID_SESSION_MESSAGE = "로그인 정보가 만료되었습니다. 다시 로그인해 주세요."
DUPLICATE_EMAIL_MESSAGE = "이미 가입된 이메일입니다."
AUTH_SESSION_MISSING_MESSAGE = "인증 세션을 생성하지 못했습니다."

EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
PASSWORD_LETTER_PATTERN = re.compile(r"[A-Za-z]")
PASSWORD_NUMBER_PATTERN = re.compile(r"[0-9]")


class StrictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SignupRequest(StrictRequest):
    email: str
    password: str
    password_confirm: str
    name: str
    birth_date: date
    calendar_type: Literal["SOLAR", "LUNAR"]
    is_leap_month: bool | None = None
    birth_time: time | None = None
    gender: Literal["MALE", "FEMALE"]

    @field_validator("email")
    @classmethod
    def validate_email(cls, value: str) -> str:
        normalized = value.strip().lower()
        if not EMAIL_PATTERN.fullmatch(normalized):
            raise ValueError("올바른 이메일 형식이 아닙니다.")
        return normalized

    @field_validator("password")
    @classmethod
    def validate_password(cls, value: str) -> str:
        if (
            len(value) < 8
            or not PASSWORD_LETTER_PATTERN.search(value)
            or not PASSWORD_NUMBER_PATTERN.search(value)
        ):
            raise ValueError("비밀번호는 영문과 숫자를 포함해 8자 이상이어야 합니다.")
        return value

    @field_validator("name")
    @classmethod
    def validate_name(cls, value: str) -> str:
        normalized = value.strip()
        if not 2 <= len(normalized) <= 4:
            raise ValueError("사용자 이름은 2자 이상 4자 이하여야 합니다.")
        return normalized

    @field_validator("calendar_type", "gender", mode="before")
    @classmethod
    def normalize_choice(cls, value: object) -> object:
        return value.upper() if isinstance(value, str) else value

    @field_validator("birth_date")
    @classmethod
    def validate_birth_date(cls, value: date) -> date:
        if not 1900 <= value.year <= date.today().year:
            raise ValueError("생년월일은 1900년부터 현재 연도까지 입력할 수 있습니다.")
        return value

    @model_validator(mode="after")
    def validate_related_fields(self) -> "SignupRequest":
        if self.password != self.password_confirm:
            raise ValueError("비밀번호와 비밀번호 확인이 일치하지 않습니다.")
        if self.calendar_type == "SOLAR":
            self.is_leap_month = None
        elif self.is_leap_month is None:
            raise ValueError("음력 생년월일은 윤달 여부가 필요합니다.")
        return self

    def signup_metadata(self) -> dict[str, str | bool | None]:
        return {
            "name": self.name,
            "birth_date": self.birth_date.isoformat(),
            "calendar_type": self.calendar_type,
            "is_leap_month": self.is_leap_month,
            "birth_time": (
                self.birth_time.isoformat(timespec="minutes")
                if self.birth_time is not None
                else None
            ),
            "gender": self.gender,
        }


class LoginRequest(StrictRequest):
    email: str
    password: str

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        return value.strip().lower()


class RefreshRequest(StrictRequest):
    refresh_token: str

    @field_validator("refresh_token")
    @classmethod
    def validate_refresh_token(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("refresh_token이 필요합니다.")
        return value


class AuthSessionResponse(BaseModel):
    user_id: str
    email: str
    access_token: str
    refresh_token: str
    token_type: str
    expires_in: int
    expires_at: int | None


def build_session_response(response: AuthResponse) -> AuthSessionResponse:
    session = response.session
    user = response.user or (session.user if session is not None else None)
    if session is None or user is None or user.email is None:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=AUTH_SESSION_MISSING_MESSAGE,
        )
    return AuthSessionResponse(
        user_id=user.id,
        email=user.email,
        access_token=session.access_token,
        refresh_token=session.refresh_token,
        token_type=session.token_type,
        expires_in=session.expires_in,
        expires_at=session.expires_at,
    )


@router.post(
    "/signup",
    response_model=AuthSessionResponse,
    status_code=status.HTTP_201_CREATED,
)
def signup(
    request: SignupRequest,
    client: Client = Depends(get_supabase_client),
) -> AuthSessionResponse:
    try:
        response = client.auth.sign_up(
            {
                "email": request.email,
                "password": request.password,
                "options": {"data": request.signup_metadata()},
            }
        )
    except AuthApiError as error:
        if error.code in {"email_exists", "user_already_exists"}:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=DUPLICATE_EMAIL_MESSAGE,
            ) from error
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="회원가입을 처리하지 못했습니다.",
        ) from error
    return build_session_response(response)


@router.post("/login", response_model=AuthSessionResponse)
def login(
    request: LoginRequest,
    client: Client = Depends(get_supabase_client),
) -> AuthSessionResponse:
    try:
        response = client.auth.sign_in_with_password(
            {"email": request.email, "password": request.password}
        )
    except AuthError as error:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=INVALID_LOGIN_MESSAGE,
        ) from error
    return build_session_response(response)


@router.post("/refresh", response_model=AuthSessionResponse)
def refresh_session(
    request: RefreshRequest,
    client: Client = Depends(get_supabase_client),
) -> AuthSessionResponse:
    try:
        response = client.auth.refresh_session(request.refresh_token)
    except AuthError as error:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=INVALID_SESSION_MESSAGE,
        ) from error
    return build_session_response(response)
