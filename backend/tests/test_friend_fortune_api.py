from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from app.friend_fortune import FriendFortuneService
from app.fortune import ConcernInterpretation
from app.fortune_api import AUTH_REQUIRED_MESSAGE, get_friend_fortune_service
from app.main import app
from tests.test_friend_fortune import (
    friend_fortune,
    friend_interpretation_request,
    friend_profile,
)


client = TestClient(app)


@pytest.fixture
def friend_service() -> Mock:
    service = Mock(spec=FriendFortuneService)
    app.dependency_overrides[get_friend_fortune_service] = lambda: service
    yield service
    app.dependency_overrides.clear()


def test_friend_fortune_requires_login() -> None:
    response = client.post(
        "/fortune/friend/today",
        json=friend_profile().model_dump(mode="json"),
    )

    assert response.status_code == 401
    assert response.json() == {"detail": AUTH_REQUIRED_MESSAGE}


def test_friend_fortune_api_returns_generated_result(friend_service: Mock) -> None:
    friend_service.create_fortune.return_value = friend_fortune()

    response = client.post(
        "/fortune/friend/today",
        json=friend_profile().model_dump(mode="json"),
    )

    assert response.status_code == 200
    assert response.json()["total_score"] == 82
    assert "love_score" not in response.json()
    friend_service.create_fortune.assert_called_once_with(friend_profile())


def test_friend_interpretation_api_returns_two_lines(friend_service: Mock) -> None:
    expected = ConcernInterpretation(
        day_message="지금까지 쌓아온 준비가 친구의 가장 단단한 행운이에요.",
        action_advice="친구가 스스로의 노력을 믿을 수 있도록 응원해주세요.",
    )
    friend_service.interpret.return_value = expected

    response = client.post(
        "/fortune/friend/interpret",
        json=friend_interpretation_request().model_dump(mode="json"),
    )

    assert response.status_code == 200
    assert response.json() == expected.model_dump(mode="json")
    friend_service.interpret.assert_called_once_with(friend_interpretation_request())


def test_friend_api_rejects_display_only_fields(friend_service: Mock) -> None:
    payload = friend_profile().model_dump(mode="json")
    payload["name"] = "행운이"
    payload["relationship"] = "친구"

    response = client.post("/fortune/friend/today", json=payload)

    assert response.status_code == 422
    friend_service.create_fortune.assert_not_called()
