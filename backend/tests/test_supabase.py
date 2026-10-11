import httpx
import pytest
from pydantic import SecretStr

from app.config import Settings
from app.supabase import (
    SupabaseConfigurationError,
    check_supabase_connection,
    get_supabase_credentials,
)


# Supabase URL과 Publishable Key가 모두 필요함을 확인한다.
def test_get_supabase_credentials_requires_both_values() -> None:
    settings = Settings(_env_file=None)

    with pytest.raises(SupabaseConfigurationError):
        get_supabase_credentials(settings)


# Supabase 연결 확인이 올바른 인증 설정 주소와 API Key를 사용하는지 확인한다.
def test_check_supabase_connection_calls_auth_settings() -> None:
    settings = Settings(
        _env_file=None,
        supabase_url="https://example.supabase.co",
        supabase_publishable_key=SecretStr("test-key"),
    )

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == "https://example.supabase.co/auth/v1/settings"
        assert request.headers["apikey"] == "test-key"
        return httpx.Response(200, json={"external": {"email": True}})

    check_supabase_connection(
        settings=settings,
        transport=httpx.MockTransport(handler),
    )

