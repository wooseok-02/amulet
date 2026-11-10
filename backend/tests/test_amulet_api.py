from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from app.amulet import AmuletList, SavedAmulet
from app.amulet_api import get_amulet_service
from app.main import app
from app.supabase import get_supabase_client
from tests.test_amulet import AMULET, png, snapshot


@pytest.fixture
def no_real_supabase():
    app.dependency_overrides[get_supabase_client] = lambda: Mock()
    yield
    app.dependency_overrides.clear()


@pytest.fixture
def api_service():
    service = Mock()
    app.dependency_overrides[get_amulet_service] = lambda: service
    yield service
    app.dependency_overrides.clear()


def test_amulet_listing_requires_login(no_real_supabase):
    assert TestClient(app).get("/amulets?month=2026-11").status_code == 401


def test_multipart_snapshot_and_png_are_passed_to_service(api_service):
    api_service.save.return_value = SavedAmulet(amulet_id=AMULET)
    response = TestClient(app).post("/amulets", data=snapshot().model_dump(mode="json"),
                                    files={"image": ("front.png", png(), "image/png")})
    assert response.status_code == 201
    assert response.json() == {"amulet_id": AMULET}
    args = api_service.save.call_args
    assert args.args[0].day_message == snapshot().day_message
    assert args.args[0].action_advice == snapshot().action_advice
    assert args.kwargs == {"share": False}


def test_blank_record_is_validation_error_not_server_error(api_service):
    data = snapshot().model_dump(mode="json")
    data["record"] = "   "
    response = TestClient(app).post("/amulets", data=data, files={"image": ("front.png", png(), "image/png")})
    assert response.status_code == 422
    api_service.save.assert_not_called()


def test_streamed_oversized_body_is_rejected_before_service(api_service):
    def chunks():
        for _ in range(8):
            yield b"x" * 1024 * 1024

    response = TestClient(app).post("/amulets", content=chunks(), headers={
        "Content-Type": "multipart/form-data; boundary=test",
    })
    assert response.status_code == 413
    api_service.save.assert_not_called()


def test_claim_rejects_invalid_token_without_calling_database(api_service):
    response = TestClient(app).post("/amulets/shares/claim", json={"token": "bad/link"})
    assert response.status_code == 422
    api_service.claim.assert_not_called()


def test_date_query_is_preserved(api_service):
    api_service.list.return_value = AmuletList(items=[])
    response = TestClient(app).get("/amulets?date=2026-11-10")
    assert response.status_code == 200
    assert response.json() == {"items": []}
    assert api_service.list.call_args.args[1].isoformat() == "2026-11-10"
