import json
from datetime import date
from unittest.mock import Mock

import httpx
import pytest
from pydantic import ValidationError

from app.friend_fortune import (
    FriendConcernInput,
    FriendFortune,
    FriendFortuneService,
    FriendInterpretationRequest,
    FriendProfileInput,
    OpenAIFriendFortuneGenerator,
)
from app.fortune import ConcernInterpretation, OpenAIFortuneGenerator


TODAY = date(2026, 10, 12)


def friend_profile(**overrides: object) -> FriendProfileInput:
    values: dict[str, object] = {
        "birth_date": "1998-03-21",
        "calendar_type": "SOLAR",
        "is_leap_month": None,
        "birth_time": None,
        "gender": None,
    }
    values.update(overrides)
    return FriendProfileInput(**values)


def friend_fortune() -> FriendFortune:
    return FriendFortune(
        fortune_date=TODAY,
        headline="준비한 만큼 자신감이 빛나는 날",
        total_comment="오늘은 익숙한 사람의 응원이 큰 힘이 되는 날이에요.",
        total_score=82,
        lucky_number=3,
        lucky_color="하늘",
        support_action="먼저 연락하기",
    )


def friend_interpretation_request() -> FriendInterpretationRequest:
    return FriendInterpretationRequest(
        profile=friend_profile(),
        fortune=friend_fortune(),
        concern=FriendConcernInput(
            category="전체",
            situation="중요한 면접을 앞둔 친구에게 자신감을 주고 싶어요.",
        ),
    )


def test_friend_service_generates_without_repository() -> None:
    generator = Mock()
    generator.generate_friend_fortune.return_value = friend_fortune()
    service = FriendFortuneService(generator, today_provider=lambda: TODAY)

    result = service.create_fortune(friend_profile())

    assert result == friend_fortune()
    generator.generate_friend_fortune.assert_called_once_with(friend_profile(), TODAY)


def test_friend_service_interprets_transient_request() -> None:
    generator = Mock()
    expected = ConcernInterpretation(
        day_message="지금까지 쌓아온 준비가 친구의 가장 단단한 행운이에요.",
        action_advice="결과를 걱정하기보다 친구가 스스로의 노력을 믿을 수 있도록 응원해주세요.",
    )
    generator.generate_friend_interpretation.return_value = expected
    service = FriendFortuneService(generator)

    result = service.interpret(friend_interpretation_request())

    assert result == expected
    generator.generate_friend_interpretation.assert_called_once_with(
        friend_interpretation_request()
    )


def test_friend_profile_supports_lunar_leap_month() -> None:
    result = friend_profile(
        calendar_type="lunar",
        is_leap_month=True,
        birth_time="08:30",
        gender="female",
    )

    assert result.calendar_type == "LUNAR"
    assert result.is_leap_month is True
    assert result.gender == "FEMALE"


@pytest.mark.parametrize(
    "values",
    [
        {"calendar_type": "LUNAR", "is_leap_month": None},
        {"birth_date": "1899-12-31"},
        {"gender": "OTHER"},
    ],
)
def test_friend_profile_rejects_invalid_input(values: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        friend_profile(**values)


@pytest.mark.parametrize(
    "values",
    [
        {"category": "게임", "situation": "고민"},
        {"category": "전체", "situation": ""},
        {"category": "전체", "situation": "가" * 201},
    ],
)
def test_friend_concern_rejects_invalid_input(values: dict[str, str]) -> None:
    with pytest.raises(ValidationError):
        FriendConcernInput(**values)


def test_openai_friend_fortune_excludes_display_only_information() -> None:
    response_body = {
        "output": [
            {
                "type": "message",
                "content": [
                    {
                        "type": "output_text",
                        "text": json.dumps(
                            friend_fortune().model_dump(
                                mode="json",
                                exclude={"fortune_date"},
                            ),
                            ensure_ascii=False,
                        ),
                    }
                ],
            }
        ]
    }

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["store"] is False
        user_data = json.loads(payload["input"][1]["content"][0]["text"])
        assert user_data["fortune_date"] == "2026-10-12"
        assert "name" not in user_data
        assert "relationship" not in user_data
        return httpx.Response(200, json=response_body)

    client = OpenAIFortuneGenerator(
        api_key="test-key",
        model="gpt-6-luna",
        transport=httpx.MockTransport(handler),
    )
    generator = OpenAIFriendFortuneGenerator(client)

    result = generator.generate_friend_fortune(friend_profile(), TODAY)

    assert result == friend_fortune()


def test_openai_friend_interpretation_sends_transient_context() -> None:
    expected = ConcernInterpretation(
        day_message="지금까지 쌓아온 준비가 친구의 가장 단단한 행운이에요.",
        action_advice="친구가 스스로의 노력을 믿을 수 있도록 응원해주세요.",
    )
    response_body = {
        "output": [
            {
                "type": "message",
                "content": [
                    {
                        "type": "output_text",
                        "text": json.dumps(expected.model_dump(), ensure_ascii=False),
                    }
                ],
            }
        ]
    }

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["model"] == "gpt-6-luna"
        assert payload["store"] is False
        assert (
            payload["text"]["format"]["name"]
            == "friend_concern_interpretation"
        )
        user_data = json.loads(payload["input"][1]["content"][0]["text"])
        assert user_data["profile"]["birth_date"] == "1998-03-21"
        assert user_data["fortune"]["total_score"] == 82
        assert user_data["concern"]["category"] == "전체"
        assert "mood" not in user_data["concern"]
        assert "name" not in user_data["profile"]
        assert "relationship" not in user_data["profile"]
        return httpx.Response(200, json=response_body)

    client = OpenAIFortuneGenerator(
        api_key="test-key",
        model="gpt-6-luna",
        transport=httpx.MockTransport(handler),
    )
    generator = OpenAIFriendFortuneGenerator(client)

    result = generator.generate_friend_interpretation(
        friend_interpretation_request()
    )

    assert result == expected
