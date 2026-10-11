import httpx
from fastapi import FastAPI, HTTPException, status

from app.config import get_settings
from app.supabase import SupabaseConfigurationError, check_supabase_connection

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    debug=settings.debug,
)


@app.get("/health", tags=["health"])
def health_check() -> dict[str, str]:
    return {
        "status": "ok",
        "environment": settings.app_env,
    }


@app.get("/health/supabase", tags=["health"])
def supabase_health_check() -> dict[str, str]:
    try:
        check_supabase_connection()
    except (SupabaseConfigurationError, httpx.HTTPError) as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Supabase connection unavailable.",
        ) from error

    return {"status": "ok"}

