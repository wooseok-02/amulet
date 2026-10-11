import json
from datetime import date
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest
from pydantic import ValidationError
from postgrest.exceptions import APIError

from app.fortune import (
    ConcernInput,
    ConcernInterpretation,
    DailyFortune,
    FortuneService,
    OpenAIFortuneGenerator,
    ProfileNotFoundError,
    SupabaseFortuneRepository,
    UserProfile,
)


TODAY = date(2026, 10, 12)


def profile() -> UserProfile:
    return UserProfile(
        id="user-123",
        name="홍길동",
        birth_date="1990-01-02",
        calendar_type="SOLAR",
        is_leap_month=None,
        birth_time="13:30",
        gender="MALE",
    )


def fortune(**overrides: object) -> DailyFortune:
    values: dict[str, object] = {
        "fortune_date": TODAY,
        "headline": "흐름이 천천히 열리는 날",
        "total_comment": "서두르기보다 준비한 것을 차분히 보여주세요.",
        "total_score": 78,
        "love_score": 72,
        "money_score": 68,
        "career_score": 81,
        "study_score": 86,
        "health_score": 74,
        "lucky_number": 7,
        "lucky_color": "초록",
        "today_action": "질문 한 번 더",
    }
    values.update(overrides)
    return DailyFortune(**values)


def concern() -> ConcernInput:
    return ConcernInput(
        category="취업",
        concern="첫 면접을 앞두고 준비한 말을 잘 전달할지 걱정돼요.",
        mood="불안해요",
    )


def test_daily_fortune_reads_profile_generates_and_requests_database_save() -> None:
    repository = Mock()
    generator = Mock()
    repository.get_daily_fortune.return_value = None
    repository.get_profile.return_value = profile()
    generator.generate_daily_fortune.return_value = fortune()
    repository.save_daily_fortune.return_value = fortune()
    service = FortuneService(repository, generator, today_provider=lambda: TODAY)

    result = service.get_daily_fortune("user-123")

    assert result == fortune()
    repository.get_daily_fortune.assert_called_once_with("user-123", TODAY)
    repository.get_profile.assert_called_once_with("user-123")
    generator.generate_daily_fortune.assert_called_once_with(profile(), TODAY)
    repository.save_daily_fortune.assert_called_once_with("user-123", fortune())


def test_daily_fortune_reuses_same_day_database_value() -> None:
    repository = Mock()
    generator = Mock()
    repository.get_daily_fortune.return_value = fortune()
    service = FortuneService(repository, generator, today_provider=lambda: TODAY)

    result = service.get_daily_fortune("user-123")

    assert result == fortune()
    repository.get_profile.assert_not_called()
    repository.save_daily_fortune.assert_not_called()
    generator.generate_daily_fortune.assert_not_called()


def test_daily_fortune_fails_when_profile_is_missing() -> None:
    repository = Mock()
    repository.get_daily_fortune.return_value = None
    repository.get_profile.return_value = None
    service = FortuneService(repository, Mock(), today_provider=lambda: TODAY)

    with pytest.raises(ProfileNotFoundError):
        service.get_daily_fortune("missing-user")


def test_concern_interpretation_uses_profile_daily_fortune_and_input() -> None:
    repository = Mock()
    generator = Mock()
    repository.get_profile.return_value = profile()
    repository.get_daily_fortune.return_value = fortune()
    expected = ConcernInterpretation(
        day_message="완벽하게 말하기보다 진심 있게 답하는 태도가 빛나는 날이에요.",
        action_advice="면접장에 들어가기 전 세 번 천천히 호흡하고 첫 문장을 소리 내어 말해보세요.",
    )
    generator.generate_concern_interpretation.return_value = expected
    service = FortuneService(repository, generator, today_provider=lambda: TODAY)

    result = service.interpret_concern("user-123", concern())

    assert result == expected
    generator.generate_concern_interpretation.assert_called_once_with(
        profile(),
        fortune(),
        concern(),
    )


@pytest.mark.parametrize(
    "overrides",
    [
        {"category": "게임"},
        {"mood": "신나요"},
        {"concern": ""},
        {"concern": "가" * 201},
    ],
)
def test_concern_rejects_invalid_input(overrides: dict[str, str]) -> None:
    values = {
        "category": "전체",
        "concern": "오늘의 고민입니다.",
        "mood": "답답해요",
        **overrides,
    }

    with pytest.raises(ValidationError):
        ConcernInput(**values)


def test_supabase_repository_sends_daily_fortune_insert() -> None:
    query = Mock()
    query.insert.return_value = query
    query.execute.return_value = SimpleNamespace(
        data=[{"user_id": "user-123", **fortune().model_dump(mode="json")}]
    )
    client = Mock()
    client.table.return_value = query
    repository = SupabaseFortuneRepository(client)

    result = repository.save_daily_fortune("user-123", fortune())

    client.table.assert_called_once_with("daily_fortunes")
    query.insert.assert_called_once_with(
        {"user_id": "user-123", **fortune().model_dump(mode="json")}
    )
    assert result == fortune()


def test_supabase_repository_reuses_saved_value_after_duplicate_insert() -> None:
    query = Mock()
    query.insert.return_value = query
    query.execute.side_effect = APIError(
        {
            "message": "duplicate key value violates unique constraint",
            "code": "23505",
            "hint": None,
            "details": None,
        }
    )
    client = Mock()
    client.table.return_value = query
    repository = SupabaseFortuneRepository(client)
    repository.get_daily_fortune = Mock(return_value=fortune())

    result = repository.save_daily_fortune("user-123", fortune())

    assert result == fortune()
    repository.get_daily_fortune.assert_called_once_with("user-123", TODAY)


def test_supabase_repository_reads_profile_by_user_id() -> None:
    query = Mock()
    query.select.return_value = query
    query.eq.return_value = query
    query.maybe_single.return_value = query
    query.execute.return_value = SimpleNamespace(data=profile().model_dump(mode="json"))
    client = Mock()
    client.table.return_value = query
    repository = SupabaseFortuneRepository(client)

    result = repository.get_profile("user-123")

    client.table.assert_called_once_with("profiles")
    query.select.assert_called_once_with(repository.PROFILE_COLUMNS)
    query.eq.assert_called_once_with("id", "user-123")
    query.maybe_single.assert_called_once_with()
    assert result == profile()


def test_supabase_repository_reads_fortune_by_user_and_date() -> None:
    query = Mock()
    query.select.return_value = query
    query.eq.return_value = query
    query.maybe_single.return_value = query
    query.execute.return_value = SimpleNamespace(data=fortune().model_dump(mode="json"))
    client = Mock()
    client.table.return_value = query
    repository = SupabaseFortuneRepository(client)

    result = repository.get_daily_fortune("user-123", TODAY)

    client.table.assert_called_once_with("daily_fortunes")
    query.select.assert_called_once_with(repository.FORTUNE_COLUMNS)
    assert query.eq.call_args_list == [
        (("user_id", "user-123"),),
        (("fortune_date", "2026-10-12"),),
    ]
    query.maybe_single.assert_called_once_with()
    assert result == fortune()


def test_openai_generator_requests_structured_daily_fortune() -> None:
    response_body = {
        "output": [
            {
                "type": "message",
                "content": [
                    {
                        "type": "output_text",
                        "text": json.dumps(
                            fortune().model_dump(
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
        assert request.url == "https://api.openai.com/v1/responses"
        assert request.headers["authorization"] == "Bearer test-key"
        payload = json.loads(request.content)
        assert payload["model"] == "gpt-6-luna"
        assert payload["store"] is False
        assert payload["reasoning"] == {"effort": "none"}
        assert payload["text"]["format"]["type"] == "json_schema"
        assert payload["text"]["format"]["strict"] is True
        user_data = json.loads(payload["input"][1]["content"][0]["text"])
        assert user_data["fortune_date"] == "2026-10-12"
        assert user_data["birth_date"] == "1990-01-02"
        return httpx.Response(200, json=response_body)

    generator = OpenAIFortuneGenerator(
        api_key="test-key",
        model="gpt-6-luna",
        transport=httpx.MockTransport(handler),
    )

    result = generator.generate_daily_fortune(profile(), TODAY)

    assert result == fortune()


def test_openai_generator_requests_structured_concern_interpretation() -> None:
    expected = ConcernInterpretation(
        day_message="진심 있게 답하는 태도가 빛나는 날이에요.",
        action_advice="첫 문장을 소리 내어 한 번 연습해보세요.",
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
        assert payload["text"]["format"]["name"] == "concern_interpretation"
        user_data = json.loads(payload["input"][1]["content"][0]["text"])
        assert user_data["category"] == "취업"
        assert user_data["mood"] == "불안해요"
        assert user_data["concern"] == concern().concern
        assert user_data["today_fortune"]["total_score"] == 78
        assert user_data["profile"]["birth_date"] == "1990-01-02"
        assert "id" not in user_data["profile"]
        assert "name" not in user_data["profile"]
        return httpx.Response(200, json=response_body)

    generator = OpenAIFortuneGenerator(
        api_key="test-key",
        model="gpt-6-luna",
        transport=httpx.MockTransport(handler),
    )

    result = generator.generate_concern_interpretation(
        profile(),
        fortune(),
        concern(),
    )

    assert result == expected
