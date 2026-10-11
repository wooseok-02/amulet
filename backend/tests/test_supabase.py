import httpx
import pytest
from pydantic import SecretStr

from app.config import Settings
from app.supabase import (
    SupabaseConfigurationError,
    check_supabase_connection,
    get_supabase_credentials,
)


def test_get_supabase_credentials_requires_both_values() -> None:
    settings = Settings(_env_file=None)

    with pytest.raises(SupabaseConfigurationError):
        get_supabase_credentials(settings)


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

