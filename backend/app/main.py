import httpx
from fastapi import FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.auth import router as auth_router
from app.config import get_settings
from app.supabase import SupabaseConfigurationError, check_supabase_connection

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    debug=settings.debug,
)
app.include_router(auth_router)


@app.exception_handler(RequestValidationError)
async def request_validation_exception_handler(
    request: Request,
    error: RequestValidationError,
) -> JSONResponse:
    del request
    first_error = error.errors()[0]
    validation_error = first_error.get("ctx", {}).get("error")
    message = (
        str(validation_error)
        if validation_error is not None
        else "입력값을 확인해 주세요."
    )
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        content={"detail": message},
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

