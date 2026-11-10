"""Amulet snapshots and private Storage access using the signed-in user's JWT."""

import calendar
import hashlib
import logging
import re
import secrets
import warnings
from datetime import date
from io import BytesIO
from typing import Annotated
from urllib.parse import quote
from uuid import UUID, uuid4

import httpx
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, field_validator

logger = logging.getLogger(__name__)
MAX_IMAGE_BYTES = 5 * 1024 * 1024
BUCKET = "amulet-images"
IMAGE_URL_SECONDS = 300
COLUMNS = "id,image_path,fortune_summary,record,day_message,action_advice,lucky_color,created_date"


class AmuletError(Exception):
    def __init__(self, status_code: int, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code


class AmuletSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    fortune_summary: Annotated[str, Field(min_length=1)]
    record: Annotated[str, Field(min_length=1, max_length=200)]
    day_message: Annotated[str, Field(min_length=1)]
    action_advice: Annotated[str, Field(min_length=1)]
    lucky_color: Annotated[str, Field(min_length=1)]
    created_date: date


class AmuletItem(AmuletSnapshot):
    amulet_id: UUID


class AmuletDetail(AmuletItem):
    image_url: str


class AmuletList(BaseModel):
    items: list[AmuletItem]


class SavedAmulet(BaseModel):
    amulet_id: UUID


class SharedAmulet(SavedAmulet):
    share_url: str


class ClaimAmulet(BaseModel):
    model_config = ConfigDict(extra="forbid")
    token: str = Field(min_length=1, max_length=128)

    @field_validator("token")
    @classmethod
    def validate_token(cls, value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", value):
            raise ValueError("올바른 공유 링크를 사용해 주세요.")
        return value


def validate_png(data: bytes, content_type: str | None) -> None:
    if len(data) > MAX_IMAGE_BYTES:
        raise AmuletError(413, "이미지는 최대 5MB까지 저장할 수 있습니다.")
    if not data or content_type != "image/png" or not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AmuletError(415, "PNG 이미지 파일을 사용해 주세요.")
    # A PNG signature alone is insufficient. Verify chunks and decode pixel data.
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as image:
                if image.format != "PNG":
                    raise ValueError("Not PNG")
                image.verify()
            with Image.open(BytesIO(data)) as image:
                image.load()
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError,
            Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
        raise AmuletError(415, "정상적인 PNG 이미지 파일을 사용해 주세요.") from error


def date_filters(month: str | None, selected_date: date | None) -> list[tuple[str, str]]:
    if (month is None) == (selected_date is None):
        raise AmuletError(422, "조회할 월 또는 날짜 중 하나를 보내주세요.")
    if selected_date is not None:
        return [("created_date", f"eq.{selected_date.isoformat()}")]
    if not re.fullmatch(r"\d{4}-\d{2}", month or ""):
        raise AmuletError(422, "월은 YYYY-MM 형식으로 보내주세요.")
    try:
        year, number = map(int, month.split("-"))
        start = date(year, number, 1)
        end = date(year, number, calendar.monthrange(year, number)[1])
    except ValueError as error:
        raise AmuletError(422, "올바른 월을 보내주세요.") from error
    return [("created_date", f"gte.{start.isoformat()}"),
            ("created_date", f"lte.{end.isoformat()}")]


class AmuletService:
    def __init__(self, client: httpx.Client, user_id: str, share_base_url: str | None) -> None:
        self.client = client
        self.user_id = user_id
        self.share_base_url = share_base_url

    def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        try:
            response = self.client.request(method, path, **kwargs)
        except httpx.HTTPError as error:
            # Commit outcome is unknown on a network failure: never remove the
            # image here, as the DB transaction may already have committed.
            raise AmuletError(503, "부적 저장소에 연결하지 못했습니다. 잠시 후 확인해 주세요.") from error
        return response

    @staticmethod
    def _check(response: httpx.Response) -> None:
        if response.is_success:
            return
        if response.status_code == 401:
            raise AmuletError(401, "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.")
        raise AmuletError(503, "부적 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.")

    def save(self, snapshot: AmuletSnapshot, image: bytes, content_type: str | None,
             *, share: bool = False) -> SavedAmulet | SharedAmulet:
        # A missing address must not create an unreachable gift or orphan upload.
        if share and not self.share_base_url:
            raise AmuletError(503, "공유 링크 주소가 아직 설정되지 않았습니다.")
        validate_png(image, content_type)
        amulet_id = uuid4()
        path = f"{self.user_id}/{amulet_id}.png"
        uploaded = self._request("POST", f"/storage/v1/object/{BUCKET}/{path}",
                                 content=image, headers={"Content-Type": "image/png", "x-upsert": "false"})
        self._check(uploaded)
        token = secrets.token_urlsafe(32) if share else None
        response = self._request("POST", "/rest/v1/rpc/save_amulet", json={
            "p_id": str(amulet_id), "p_image_path": path,
            "p_content": snapshot.model_dump(mode="json"),
            "p_token_hash": hashlib.sha256(token.encode()).hexdigest() if token else None,
        })
        if not response.is_success:
            # A definite RPC error rolls back its transaction. Storage policy
            # additionally prevents deletion of any successfully committed image.
            try:
                cleanup = self._request("DELETE", f"/storage/v1/object/{BUCKET}", json={"prefixes": [path]})
                if not cleanup.is_success:
                    logger.warning("Amulet orphan cleanup requires retry: %s", amulet_id)
            except AmuletError:
                logger.warning("Amulet orphan cleanup unavailable: %s", amulet_id)
            self._check(response)
        try:
            stored_id = UUID(response.json())
            if stored_id != amulet_id:
                raise ValueError("Unexpected saved ID")
        except (ValueError, TypeError, AttributeError) as error:
            raise AmuletError(503, "저장 결과를 확인하지 못했습니다. 보관함을 확인해 주세요.") from error
        if token is not None:
            return SharedAmulet(amulet_id=amulet_id, share_url=f"{self.share_base_url.rstrip('/')}/share/{token}")
        return SavedAmulet(amulet_id=amulet_id)

    def claim(self, token: str) -> SavedAmulet:
        response = self._request("POST", "/rest/v1/rpc/claim_amulet",
                                 json={"p_token_hash": hashlib.sha256(token.encode()).hexdigest()})
        if not response.is_success:
            try:
                error_code = response.json().get("code")
                message = response.json().get("message")
            except (ValueError, AttributeError):
                error_code, message = None, None
            if error_code == "P0002":
                raise AmuletError(404, "공유 링크를 찾을 수 없습니다.")
            if error_code == "P0001" and message == "AMULET_ALREADY_CLAIMED":
                raise AmuletError(409, "다른 계정이 이미 받은 부적입니다.")
            self._check(response)
        try:
            return SavedAmulet(amulet_id=UUID(response.json()))
        except (ValueError, TypeError, AttributeError) as error:
            raise AmuletError(503, "수령 결과를 확인하지 못했습니다. 보관함을 확인해 주세요.") from error

    def list(self, month: str | None, selected_date: date | None) -> AmuletList:
        filters = date_filters(month, selected_date)
        # Range through every page so a busy month is not silently truncated at
        # the project's PostgREST row cap. RLS restricts all rows to this library.
        items = []
        offset = 0
        while True:
            response = self._request("GET", "/rest/v1/amulets", params=[
                ("select", COLUMNS), ("order", "created_date.asc,id.asc"),
                ("offset", str(offset)), ("limit", "100"), *filters,
            ])
            self._check(response)
            rows = response.json()
            for row in rows:
                items.append(self._item(row))
            if not rows:
                return AmuletList(items=items)
            offset += len(rows)

    @staticmethod
    def _item(row: dict) -> AmuletItem:
        return AmuletItem(amulet_id=row["id"], **{key: row[key] for key in AmuletSnapshot.model_fields})

    def detail(self, amulet_id: UUID) -> AmuletDetail:
        response = self._request("GET", "/rest/v1/amulets", params={"select": COLUMNS, "id": f"eq.{amulet_id}"})
        self._check(response)
        rows = response.json()
        if not rows:
            raise AmuletError(404, "보관함에서 부적을 찾을 수 없습니다.")
        row = rows[0]
        # Re-read authorization through Storage RLS before issuing a 5-minute URL.
        result = self._request("POST", f"/storage/v1/object/sign/{BUCKET}/{quote(row['image_path'], safe='/')}",
                               json={"expiresIn": IMAGE_URL_SECONDS})
        self._check(result)
        signed = result.json().get("signedURL")
        if not isinstance(signed, str) or not signed.startswith("/object/sign/"):
            raise AmuletError(503, "이미지 주소를 확인하지 못했습니다.")
        image_url = f"{str(self.client.base_url).rstrip('/')}/storage/v1{signed}"
        return AmuletDetail(**self._item(row).model_dump(), image_url=image_url)
