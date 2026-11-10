import hashlib
import json
from datetime import date
from io import BytesIO
from uuid import UUID

import httpx
import pytest
from PIL import Image

from app.amulet import (
    MAX_IMAGE_BYTES, AmuletError, AmuletService, AmuletSnapshot, date_filters, validate_png,
)

USER = "10000000-0000-4000-8000-000000000001"
AMULET = "20000000-0000-4000-8000-000000000001"


def png() -> bytes:
    output = BytesIO()
    Image.new("RGB", (16, 16), "green").save(output, format="PNG")
    return output.getvalue()


def snapshot() -> AmuletSnapshot:
    return AmuletSnapshot(fortune_summary="당시 운세", record="면접이 걱정돼요.",
                          day_message="차분히 해도 괜찮아요.", action_advice="첫 문장을 연습해보세요.",
                          lucky_color="초록", created_date="2026-11-10")


def service(handler, share_url="https://example.test") -> AmuletService:
    client = httpx.Client(base_url="https://example.supabase.co", transport=httpx.MockTransport(handler),
                         headers={"Authorization": "Bearer user-token", "apikey": "publishable"})
    return AmuletService(client, USER, share_url)


def test_only_real_complete_png_is_accepted():
    validate_png(png(), "image/png")
    for data, mime in [(b"not an image", "image/png"), (png(), "image/jpeg"),
                       (png()[:40], "image/png"), (b"", "image/png")]:
        with pytest.raises(AmuletError) as result:
            validate_png(data, mime)
        assert result.value.status_code == 415
    with pytest.raises(AmuletError) as result:
        validate_png(b"x" * (MAX_IMAGE_BYTES + 1), "image/png")
    assert result.value.status_code == 413


def test_own_snapshot_preserves_record_and_both_interpretation_lines():
    calls = []

    def handler(request):
        calls.append(request)
        assert request.headers["Authorization"] == "Bearer user-token"
        if request.url.path.startswith("/storage/"):
            assert request.content == png()
            assert request.headers["x-upsert"] == "false"
            return httpx.Response(200, json={})
        payload = json.loads(request.content)
        assert payload["p_content"] == snapshot().model_dump(mode="json")
        assert payload["p_token_hash"] is None
        assert payload["p_image_path"] == f"{USER}/{payload['p_id']}.png"
        return httpx.Response(200, json=payload["p_id"])

    result = service(handler).save(snapshot(), png(), "image/png")
    assert isinstance(result.amulet_id, UUID)
    assert len(calls) == 2


def test_missing_share_address_does_not_upload_anything():
    def handler(request):
        pytest.fail("Missing share configuration must not make network calls")

    with pytest.raises(AmuletError) as result:
        service(handler, None).save(snapshot(), png(), "image/png", share=True)
    assert result.value.status_code == 503


def test_share_stores_hash_and_returns_original_token_only_in_link():
    stored = {}

    def handler(request):
        if request.url.path == "/rest/v1/rpc/save_amulet":
            stored.update(json.loads(request.content))
            return httpx.Response(200, json=stored["p_id"])
        return httpx.Response(200, json={})

    result = service(handler).save(snapshot(), png(), "image/png", share=True)
    token = result.share_url.rsplit("/", 1)[-1]
    assert len(token) == 43
    assert stored["p_token_hash"] == hashlib.sha256(token.encode()).hexdigest()
    assert token not in json.dumps(stored)


def test_definite_database_error_attempts_orphan_cleanup():
    calls = []

    def handler(request):
        calls.append((request.method, request.url.path))
        return httpx.Response(400 if request.url.path.startswith("/rest/") else 200, json={})

    with pytest.raises(AmuletError):
        service(handler).save(snapshot(), png(), "image/png")
    assert calls[-1] == ("DELETE", "/storage/v1/object/amulet-images")


def test_unknown_commit_outcome_does_not_delete_image():
    calls = []

    def handler(request):
        calls.append(request.method)
        if request.url.path.startswith("/rest/"):
            raise httpx.ReadTimeout("No response", request=request)
        return httpx.Response(200, json={})

    with pytest.raises(AmuletError):
        service(handler).save(snapshot(), png(), "image/png")
    assert calls == ["POST", "POST"]


@pytest.mark.parametrize("code,message,status", [
    ("P0002", "AMULET_LINK_NOT_FOUND", 404),
    ("P0001", "AMULET_ALREADY_CLAIMED", 409),
])
def test_claim_maps_database_result(code, message, status):
    def handler(request):
        assert json.loads(request.content)["p_token_hash"] == hashlib.sha256(b"a" * 43).hexdigest()
        return httpx.Response(400, json={"code": code, "message": message})

    with pytest.raises(AmuletError) as result:
        service(handler).claim("a" * 43)
    assert result.value.status_code == status


def test_month_handles_leap_day_and_invalid_filters():
    assert date_filters("2028-02", None)[-1] == ("created_date", "lte.2028-02-29")
    assert date_filters(None, date(2026, 11, 10)) == [("created_date", "eq.2026-11-10")]
    for month, selected in [(None, None), ("2026-11", date.today()), ("2026-13", None), ("2026-1", None)]:
        with pytest.raises(AmuletError) as result:
            date_filters(month, selected)
        assert result.value.status_code == 422


def test_list_does_not_silently_drop_rows_at_server_row_cap():
    offsets = []
    row = {"id": AMULET, "image_path": f"{USER}/{AMULET}.png", **snapshot().model_dump(mode="json")}

    def handler(request):
        offsets.append(request.url.params["offset"])
        return httpx.Response(200, json=[row] if len(offsets) <= 2 else [])

    result = service(handler).list("2026-11", None)
    assert len(result.items) == 2
    assert offsets == ["0", "1", "2"]


def test_detail_checks_membership_before_signing_image():
    calls = []

    def handler(request):
        calls.append(request.url.path)
        return httpx.Response(200, json=[])

    with pytest.raises(AmuletError) as result:
        service(handler).detail(UUID(AMULET))
    assert result.value.status_code == 404
    assert calls == ["/rest/v1/amulets"]


def test_detail_issues_short_lived_private_url_without_regenerating():
    row = {"id": AMULET, "image_path": f"{USER}/{AMULET}.png", **snapshot().model_dump(mode="json")}

    def handler(request):
        if request.url.path.startswith("/rest/"):
            return httpx.Response(200, json=[row])
        assert json.loads(request.content) == {"expiresIn": 300}
        return httpx.Response(200, json={"signedURL": f"/object/sign/amulet-images/{USER}/{AMULET}.png?token=temporary"})

    result = service(handler).detail(UUID(AMULET))
    assert result.day_message == snapshot().day_message
    assert result.action_advice == snapshot().action_advice
    assert result.record == snapshot().record
    assert result.image_url.startswith("https://example.supabase.co/storage/v1/object/sign/")
