"""Bound incoming multipart requests before Starlette spools uploaded files."""

from starlette.responses import JSONResponse
from starlette.exceptions import HTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.amulet import MAX_IMAGE_BYTES

MAX_UPLOAD_BODY = MAX_IMAGE_BYTES + 64 * 1024


class UploadTooLarge(HTTPException):
    def __init__(self) -> None:
        super().__init__(413, "이미지 업로드 요청이 너무 큽니다.")


class AmuletUploadLimit:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if (scope["type"] != "http" or scope.get("method") != "POST"
                or scope.get("path", "").rstrip("/") not in ("/amulets", "/amulets/shares")):
            await self.app(scope, receive, send)
            return
        seen = 0

        async def limited_receive() -> Message:
            nonlocal seen
            message = await receive()
            seen += len(message.get("body", b""))
            if seen > MAX_UPLOAD_BODY:
                raise UploadTooLarge()
            return message

        try:
            await self.app(scope, limited_receive, send)
        except UploadTooLarge:
            await JSONResponse({"detail": "이미지 업로드 요청이 너무 큽니다."}, status_code=413)(scope, receive, send)
