"""Installation page, APK delivery and Android domain association."""

import re
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, RedirectResponse, Response
from pydantic import BaseModel

from app.config import Settings, get_settings

router = APIRouter(tags=["app-distribution"])
INSTALL_PAGE = Path(__file__).with_name("static") / "install.html"


class InstallationInfo(BaseModel):
    apk_available: bool
    download_url: str | None


def available_apk(settings: Settings) -> bool:
    return bool((settings.amulet_apk_file_path and settings.amulet_apk_file_path.is_file())
                or settings.amulet_apk_download_url)


def installation_info(request: Request, settings: Settings) -> InstallationInfo:
    available = available_apk(settings)
    return InstallationInfo(apk_available=available,
                            download_url=str(request.url_for("download_apk")) if available else None)


@router.get("/app/install", response_model=InstallationInfo)
def get_installation_info(request: Request, settings: Settings = Depends(get_settings)):
    return installation_info(request, settings)


def install_page() -> FileResponse:
    return FileResponse(
        INSTALL_PAGE,
        media_type="text/html",
        headers={"Cache-Control": "no-cache", "X-Content-Type-Options": "nosniff"},
    )


@router.get("/install", response_class=FileResponse, include_in_schema=False)
def get_install_page():
    return install_page()


@router.get("/app/download", response_class=FileResponse,
            responses={307: {"description": "설정된 APK 저장소로 이동"},
                       503: {"description": "APK 미등록"}})
def download_apk(settings: Settings = Depends(get_settings)):
    path: Path | None = settings.amulet_apk_file_path
    headers = {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"}
    if path and path.is_file():
        return FileResponse(path, media_type="application/vnd.android.package-archive",
                            filename="bukku.apk", headers=headers)
    if settings.amulet_apk_download_url:
        return RedirectResponse(str(settings.amulet_apk_download_url), status_code=307, headers=headers)
    raise HTTPException(503, "앱 설치 파일이 아직 등록되지 않았습니다.")


@router.get(
    "/share/{token}",
    response_model=None,
    responses={200: {"description": "브라우저 설치 안내 또는 설치 정보 JSON"}},
)
def get_share_installation_info(token: str, request: Request,
                                settings: Settings = Depends(get_settings)) -> Response | InstallationInfo:
    # This only checks syntax. Do not reveal gift data or claim on public GET.
    if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        raise HTTPException(404, "공유 링크를 확인해 주세요.")
    if "text/html" in request.headers.get("accept", "").lower():
        return install_page()
    return installation_info(request, settings)


@router.get("/.well-known/assetlinks.json")
def android_asset_links(settings: Settings = Depends(get_settings)):
    if not settings.android_package_name or not settings.android_sha256_cert_fingerprints:
        raise HTTPException(503, "Android 앱 연결 정보가 아직 등록되지 않았습니다.")
    return [{"relation": ["delegate_permission/common.handle_all_urls"],
             "target": {"namespace": "android_app", "package_name": settings.android_package_name,
                        "sha256_cert_fingerprints": settings.android_sha256_cert_fingerprints}}]
