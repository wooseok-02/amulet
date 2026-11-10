"""Public installation guidance; opening a link never claims a gift."""

import re
from html import escape

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import HTMLResponse

from app.config import Settings, get_settings

router = APIRouter()


@router.get("/share/{token}", response_class=HTMLResponse, include_in_schema=False)
def share_installation_page(token: str, settings: Settings = Depends(get_settings)) -> HTMLResponse:
    if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        raise HTTPException(404, "공유 링크를 확인해 주세요.")
    if settings.amulet_apk_download_url:
        download = ('<a class="download" href="'
                    + escape(str(settings.amulet_apk_download_url), quote=True)
                    + '">부꾸 앱 다운로드</a>')
    else:
        download = "<p>앱 설치 파일을 준비 중이에요. 부적을 보내준 분에게 설치 방법을 확인해 주세요.</p>"
    page = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>부꾸 · 선물 받은 부적</title><style>
body{{font-family:system-ui,sans-serif;background:#fff8e9;color:#49382d;margin:0;padding:48px 24px}}
main{{max-width:480px;margin:0 auto}}h1{{font-size:26px}}p,li{{line-height:1.8}}
.download{{display:inline-block;background:#385b44;color:white;padding:14px 20px;border-radius:10px;text-decoration:none}}
</style></head><body><main><h1>선물 받은 부적이 있어요</h1>
<p>부꾸 앱에서 부적을 받아 보관할 수 있어요.</p>
<ol><li>부꾸 앱을 설치해 주세요.</li><li>앱에서 로그인하거나 회원가입해 주세요.</li>
<li>카카오톡으로 돌아가 원래 공유 링크를 다시 눌러주세요.</li></ol>
{download}<p>이미 앱을 설치했다면 앱에서 로그인한 뒤 원래 링크를 다시 열어주세요.</p>
</main></body></html>"""
    return HTMLResponse(page, headers={
        "Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff",
        "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
    })
