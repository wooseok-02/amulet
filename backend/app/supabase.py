import httpx
from supabase import Client, create_client

from app.config import Settings, get_settings


class SupabaseConfigurationError(RuntimeError):
    pass


def get_supabase_credentials(settings: Settings) -> tuple[str, str]:
    if settings.supabase_url is None or settings.supabase_publishable_key is None:
        raise SupabaseConfigurationError(
            "SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY must be configured."
        )

    return (
        str(settings.supabase_url).rstrip("/"),
        settings.supabase_publishable_key.get_secret_value(),
    )


def get_supabase_client() -> Client:
    """Create an isolated client so auth sessions cannot leak between requests."""
    url, publishable_key = get_supabase_credentials(get_settings())
    return create_client(url, publishable_key)


def check_supabase_connection(
    settings: Settings | None = None,
    transport: httpx.BaseTransport | None = None,
) -> None:
    current_settings = settings or get_settings()
    url, publishable_key = get_supabase_credentials(current_settings)

    with httpx.Client(transport=transport, timeout=5.0) as client:
        response = client.get(
            f"{url}/auth/v1/settings",
            headers={"apikey": publishable_key},
        )
        response.raise_for_status()

