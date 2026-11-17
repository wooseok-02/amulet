import logging
from datetime import date, time
from typing import Literal

import httpx
from fastapi import APIRouter, Depends, HTTPException, Response, status
from postgrest.exceptions import APIError
from pydantic import BaseModel, field_serializer

from app.config import Settings, get_settings
from app.fortune_api import AuthenticatedSupabase, get_authenticated_supabase
from app.supabase import get_supabase_credentials


router = APIRouter(prefix="/api/v1", tags=["mypage"])
logger = logging.getLogger(__name__)


class MyProfileResponse(BaseModel):
    name: str
    email: str
    birthDate: date
    calendarType: Literal["SOLAR", "LUNAR"]
    isLeapMonth: bool
    birthTime: time | None
    gender: Literal["MALE", "FEMALE"]

    @field_serializer("birthTime")
    def serialize_birth_time(self, value: time | None) -> str | None:
        return value.isoformat(timespec="minutes") if value is not None else None


@router.get("/me", response_model=MyProfileResponse)
def get_me(auth: AuthenticatedSupabase = Depends(get_authenticated_supabase)) -> MyProfileResponse:
    if not auth.email:
        raise HTTPException(status_code=502, detail="계정 이메일을 확인하지 못했습니다.")
    try:
        result = (
            auth.client.table("profiles")
            .select("name,birth_date,calendar_type,is_leap_month,birth_time,gender")
            .eq("id", auth.user_id)
            .maybe_single()
            .execute()
        )
    except APIError as error:
        logger.exception("My profile lookup failed for user %s", auth.user_id)
        raise HTTPException(status_code=502, detail="프로필을 불러오지 못했습니다.") from error

    if result is None or result.data is None:
        raise HTTPException(status_code=404, detail="프로필을 찾을 수 없습니다.")

    profile = result.data
    # Email is owned by Supabase Auth; profile data is read under the caller's RLS.
    return MyProfileResponse(
        name=profile["name"],
        email=auth.email,
        birthDate=profile["birth_date"],
        calendarType=profile["calendar_type"],
        isLeapMonth=bool(profile["is_leap_month"]),
        birthTime=profile["birth_time"],
        gender=profile["gender"],
    )


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(
    auth: AuthenticatedSupabase = Depends(get_authenticated_supabase),
    settings: Settings = Depends(get_settings),
) -> Response:
    url, key = get_supabase_credentials(settings)
    try:
        with httpx.Client(timeout=5.0) as client:
            result = client.post(
                f"{url}/auth/v1/logout",
                params={"scope": "local"},
                headers={"apikey": key, "Authorization": f"Bearer {auth.access_token}"},
            )
    except httpx.HTTPError as error:
        logger.exception("Supabase logout request failed")
        raise HTTPException(status_code=502, detail="로그아웃을 처리하지 못했습니다.") from error

    if result.status_code != 204:
        logger.error("Supabase logout returned status %s", result.status_code)
        raise HTTPException(status_code=502, detail="로그아웃을 처리하지 못했습니다.")
    return Response(status_code=204)
