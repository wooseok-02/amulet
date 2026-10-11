import json
from collections.abc import Callable
from datetime import date, datetime, time
from typing import Literal, Protocol
from zoneinfo import ZoneInfo

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
from postgrest.exceptions import APIError
from supabase import Client


SEOUL_TIMEZONE = ZoneInfo("Asia/Seoul")

FortuneCategory = Literal[
    "전체",
    "연애",
    "금전",
    "취업",
    "학업",
    "건강",
    "인간관계",
]
Mood = Literal["불안해요", "우울해요", "답답해요", "용기가 필요해요"]


class FortuneError(RuntimeError):
    pass


class ProfileNotFoundError(FortuneError):
    pass


class FortuneGenerationError(FortuneError):
    pass


class UserProfile(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    name: str
    birth_date: date
    calendar_type: Literal["SOLAR", "LUNAR"]
    is_leap_month: bool | None = None
    birth_time: time | None = None
    gender: Literal["MALE", "FEMALE"]


class DailyFortune(BaseModel):
    model_config = ConfigDict(extra="ignore")

    fortune_date: date
    headline: str
    total_comment: str
    total_score: int = Field(ge=0, le=100)
    love_score: int = Field(ge=0, le=100)
    money_score: int = Field(ge=0, le=100)
    career_score: int = Field(ge=0, le=100)
    study_score: int = Field(ge=0, le=100)
    health_score: int = Field(ge=0, le=100)
    lucky_number: int = Field(ge=0, le=10)
    lucky_color: str
    today_action: str

    @field_validator("headline", "total_comment", "lucky_color", "today_action")
    @classmethod
    def normalize_single_line(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("빈 문장은 사용할 수 없습니다.")
        return normalized


class ConcernInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: FortuneCategory
    concern: str = Field(max_length=200)
    mood: Mood

    @field_validator("concern")
    @classmethod
    def validate_concern(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("고민 내용을 입력해 주세요.")
        return normalized


class ConcernInterpretation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    day_message: str
    action_advice: str

    @field_validator("day_message", "action_advice")
    @classmethod
    def normalize_single_line(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("빈 문장은 사용할 수 없습니다.")
        return normalized


class FortuneRepository(Protocol):
    def get_profile(self, user_id: str) -> UserProfile | None: ...

    def get_daily_fortune(
        self,
        user_id: str,
        fortune_date: date,
    ) -> DailyFortune | None: ...

    def save_daily_fortune(
        self,
        user_id: str,
        fortune: DailyFortune,
    ) -> DailyFortune: ...


class FortuneGenerator(Protocol):
    def generate_daily_fortune(
        self,
        profile: UserProfile,
        fortune_date: date,
    ) -> DailyFortune: ...

    def generate_concern_interpretation(
        self,
        profile: UserProfile,
        fortune: DailyFortune,
        concern: ConcernInput,
    ) -> ConcernInterpretation: ...


class SupabaseFortuneRepository:
    PROFILE_COLUMNS = (
        "id,name,birth_date,calendar_type,is_leap_month,birth_time,gender"
    )
    FORTUNE_COLUMNS = (
        "fortune_date,headline,total_comment,total_score,love_score,money_score,"
        "career_score,study_score,health_score,lucky_number,lucky_color,today_action"
    )

    def __init__(self, client: Client) -> None:
        self.client = client

    def get_profile(self, user_id: str) -> UserProfile | None:
        try:
            response = (
                self.client.table("profiles")
                .select(self.PROFILE_COLUMNS)
                .eq("id", user_id)
                .maybe_single()
                .execute()
            )
        except APIError as error:
            raise FortuneError("사용자 프로필을 불러오지 못했습니다.") from error
        return UserProfile.model_validate(response.data) if response.data else None

    def get_daily_fortune(
        self,
        user_id: str,
        fortune_date: date,
    ) -> DailyFortune | None:
        try:
            response = (
                self.client.table("daily_fortunes")
                .select(self.FORTUNE_COLUMNS)
                .eq("user_id", user_id)
                .eq("fortune_date", fortune_date.isoformat())
                .maybe_single()
                .execute()
            )
        except APIError as error:
            raise FortuneError("오늘 운세를 불러오지 못했습니다.") from error
        return DailyFortune.model_validate(response.data) if response.data else None

    def save_daily_fortune(
        self,
        user_id: str,
        fortune: DailyFortune,
    ) -> DailyFortune:
        payload = {
            "user_id": user_id,
            **fortune.model_dump(mode="json"),
        }
        try:
            response = self.client.table("daily_fortunes").insert(payload).execute()
        except APIError as error:
            if error.code == "23505":
                existing = self.get_daily_fortune(user_id, fortune.fortune_date)
                if existing is not None:
                    return existing
            raise FortuneError("오늘 운세를 저장하지 못했습니다.") from error
        if not response.data:
            raise FortuneError("오늘 운세를 저장하지 못했습니다.")
        row = response.data[0] if isinstance(response.data, list) else response.data
        return DailyFortune.model_validate(row)


class OpenAIFortuneGenerator:
    DAILY_SCHEMA = {
        "type": "object",
        "properties": {
            "headline": {"type": "string"},
            "total_comment": {"type": "string"},
            "total_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "love_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "money_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "career_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "study_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "health_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "lucky_number": {"type": "integer", "minimum": 0, "maximum": 10},
            "lucky_color": {"type": "string"},
            "today_action": {"type": "string"},
        },
        "required": [
            "headline",
            "total_comment",
            "total_score",
            "love_score",
            "money_score",
            "career_score",
            "study_score",
            "health_score",
            "lucky_number",
            "lucky_color",
            "today_action",
        ],
        "additionalProperties": False,
    }
    INTERPRETATION_SCHEMA = {
        "type": "object",
        "properties": {
            "day_message": {"type": "string"},
            "action_advice": {"type": "string"},
        },
        "required": ["day_message", "action_advice"],
        "additionalProperties": False,
    }

    def __init__(
        self,
        api_key: str,
        model: str,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not api_key:
            raise FortuneGenerationError("OPENAI_API_KEY가 필요합니다.")
        if not model:
            raise FortuneGenerationError("OPENAI_MODEL이 필요합니다.")
        self.api_key = api_key
        self.model = model
        self.transport = transport

    def generate_daily_fortune(
        self,
        profile: UserProfile,
        fortune_date: date,
    ) -> DailyFortune:
        request_data = {
            "fortune_date": fortune_date.isoformat(),
            "birth_date": profile.birth_date.isoformat(),
            "calendar_type": profile.calendar_type,
            "is_leap_month": profile.is_leap_month,
            "birth_time": (
                profile.birth_time.isoformat(timespec="minutes")
                if profile.birth_time is not None
                else None
            ),
            "gender": profile.gender,
        }
        result = self.request_structured_output(
            schema_name="daily_fortune",
            schema=self.DAILY_SCHEMA,
            instructions=(
                "너는 한국어 오늘 운세 작성자다. 입력받은 생년월일, 양력/음력, "
                "윤달, 출생 시간, 성별과 기준 날짜를 바탕으로 오늘 운세를 작성한다. "
                "모든 문장은 화면에 바로 표시할 수 있는 자연스러운 한국어 한 줄로 쓴다. "
                "행운 색은 색 이름만 쓴다. 단정적인 사고·질병·죽음 예측이나 의료 조언은 하지 않는다."
            ),
            user_data=request_data,
        )
        try:
            return DailyFortune(fortune_date=fortune_date, **result)
        except ValidationError as error:
            raise FortuneGenerationError("운세 생성 결과 형식이 올바르지 않습니다.") from error

    def generate_concern_interpretation(
        self,
        profile: UserProfile,
        fortune: DailyFortune,
        concern: ConcernInput,
    ) -> ConcernInterpretation:
        request_data = {
            "profile": profile.model_dump(mode="json", exclude={"id", "name"}),
            "today_fortune": fortune.model_dump(mode="json"),
            "category": concern.category,
            "concern": concern.concern,
            "mood": concern.mood,
        }
        result = self.request_structured_output(
            schema_name="concern_interpretation",
            schema=self.INTERPRETATION_SCHEMA,
            instructions=(
                "너는 사용자의 오늘 운세와 고민을 연결해 짧게 응원하는 한국어 상담자다. "
                "day_message에는 오늘이 어떤 날인지 한 줄로 쓰고, action_advice에는 지금 "
                "실천할 구체적인 행동을 한 줄로 쓴다. 과장하거나 운세를 사실처럼 단정하지 말고, "
                "질병 진단이나 의료 조언은 하지 않는다."
            ),
            user_data=request_data,
        )
        try:
            return ConcernInterpretation.model_validate(result)
        except ValidationError as error:
            raise FortuneGenerationError("고민 해석 결과 형식이 올바르지 않습니다.") from error

    def request_structured_output(
        self,
        *,
        schema_name: str,
        schema: dict[str, object],
        instructions: str,
        user_data: dict[str, object],
    ) -> dict[str, object]:
        payload = {
            "model": self.model,
            "store": False,
            "reasoning": {"effort": "none"},
            "input": [
                {
                    "role": "system",
                    "content": [{"type": "input_text", "text": instructions}],
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_text",
                            "text": json.dumps(user_data, ensure_ascii=False),
                        }
                    ],
                },
            ],
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": schema_name,
                    "strict": True,
                    "schema": schema,
                }
            },
        }
        try:
            with httpx.Client(transport=self.transport, timeout=30.0) as client:
                response = client.post(
                    "https://api.openai.com/v1/responses",
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
                response.raise_for_status()
                response_data = response.json()
            output_text = self._extract_output_text(response_data)
            parsed = json.loads(output_text)
        except (httpx.HTTPError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise FortuneGenerationError("운세 생성에 실패했습니다.") from error
        if not isinstance(parsed, dict):
            raise FortuneGenerationError("운세 생성 결과 형식이 올바르지 않습니다.")
        return parsed

    @staticmethod
    def _extract_output_text(response_data: dict[str, object]) -> str:
        for output in response_data.get("output", []):
            if not isinstance(output, dict):
                continue
            for content in output.get("content", []):
                if isinstance(content, dict) and content.get("type") == "output_text":
                    text = content.get("text")
                    if isinstance(text, str):
                        return text
        raise FortuneGenerationError("OpenAI 응답에 결과가 없습니다.")


class FortuneService:
    def __init__(
        self,
        repository: FortuneRepository,
        generator: FortuneGenerator,
        today_provider: Callable[[], date] | None = None,
    ) -> None:
        self.repository = repository
        self.generator = generator
        self.today_provider = today_provider or self._seoul_today

    @staticmethod
    def _seoul_today() -> date:
        return datetime.now(SEOUL_TIMEZONE).date()

    def get_daily_fortune(self, user_id: str) -> DailyFortune:
        today = self.today_provider()
        existing = self.repository.get_daily_fortune(user_id, today)
        if existing is not None:
            return existing

        profile = self._get_profile(user_id)
        generated = self.generator.generate_daily_fortune(profile, today)
        return self.repository.save_daily_fortune(user_id, generated)

    def interpret_concern(
        self,
        user_id: str,
        concern: ConcernInput,
    ) -> ConcernInterpretation:
        profile = self._get_profile(user_id)
        fortune = self.get_daily_fortune(user_id)
        return self.generator.generate_concern_interpretation(
            profile,
            fortune,
            concern,
        )

    def _get_profile(self, user_id: str) -> UserProfile:
        profile = self.repository.get_profile(user_id)
        if profile is None:
            raise ProfileNotFoundError("사용자 프로필을 찾을 수 없습니다.")
        return profile
