from collections.abc import Iterator
from datetime import date
from typing import Annotated
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.concurrency import run_in_threadpool
from pydantic import ValidationError

from app.amulet import (
    MAX_IMAGE_BYTES, AmuletDetail, AmuletList, AmuletService, AmuletSnapshot,
    ClaimAmulet, SavedAmulet, SharedAmulet,
)
from app.config import Settings, get_settings
from app.fortune_api import AuthenticatedSupabase, get_authenticated_supabase
from app.supabase import SupabaseConfigurationError, get_supabase_credentials

router = APIRouter(prefix="/amulets", tags=["amulets"])


def get_amulet_service(
    auth: AuthenticatedSupabase = Depends(get_authenticated_supabase),
    settings: Settings = Depends(get_settings),
) -> Iterator[AmuletService]:
    try:
        url, key = get_supabase_credentials(settings)
    except SupabaseConfigurationError as error:
        raise HTTPException(503, "부적 저장소가 설정되지 않았습니다.") from error
    with httpx.Client(base_url=url, headers={
        "apikey": key, "Authorization": f"Bearer {auth.access_token}",
    }, timeout=20.0) as client:
        yield AmuletService(client, auth.user_id,
                            str(settings.amulet_share_base_url) if settings.amulet_share_base_url else None)


def upload_snapshot(
    fortune_summary: Annotated[str, Form()],
    record: Annotated[str, Form()],
    day_message: Annotated[str, Form()],
    action_advice: Annotated[str, Form()],
    lucky_color: Annotated[str, Form()],
    created_date: Annotated[date, Form()],
) -> AmuletSnapshot:
    try:
        return AmuletSnapshot(fortune_summary=fortune_summary, record=record,
                              day_message=day_message, action_advice=action_advice,
                              lucky_color=lucky_color, created_date=created_date)
    except ValidationError as error:
        raise HTTPException(422, "부적에 저장할 문구를 확인해 주세요. 기록은 1~200자입니다.") from error


async def save_upload(image: UploadFile, snapshot: AmuletSnapshot,
                      service: AmuletService, *, share: bool):
    try:
        data = await image.read(MAX_IMAGE_BYTES + 1)
        return await run_in_threadpool(service.save, snapshot, data, image.content_type, share=share)
    finally:
        await image.close()


@router.post("", response_model=SavedAmulet, status_code=201)
async def save_own_amulet(
    image: Annotated[UploadFile, File()],
    snapshot: AmuletSnapshot = Depends(upload_snapshot),
    service: AmuletService = Depends(get_amulet_service),
) -> SavedAmulet:
    return await save_upload(image, snapshot, service, share=False)


@router.post("/shares", response_model=SharedAmulet, status_code=201)
async def create_friend_share(
    image: Annotated[UploadFile, File()],
    snapshot: AmuletSnapshot = Depends(upload_snapshot),
    service: AmuletService = Depends(get_amulet_service),
) -> SharedAmulet:
    return await save_upload(image, snapshot, service, share=True)


@router.post("/shares/claim", response_model=SavedAmulet)
def claim_friend_amulet(request: ClaimAmulet,
                        service: AmuletService = Depends(get_amulet_service)) -> SavedAmulet:
    return service.claim(request.token)


@router.get("", response_model=AmuletList)
def list_amulets(
    month: Annotated[str | None, Query()] = None,
    selected_date: Annotated[date | None, Query(alias="date")] = None,
    service: AmuletService = Depends(get_amulet_service),
) -> AmuletList:
    return service.list(month, selected_date)


@router.get("/{amulet_id}", response_model=AmuletDetail)
def get_amulet(amulet_id: UUID,
               service: AmuletService = Depends(get_amulet_service)) -> AmuletDetail:
    return service.detail(amulet_id)
