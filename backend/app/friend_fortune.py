from collections.abc import Callable
from datetime import date, datetime, time
from typing import Literal, Protocol

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)

from app.fortune import (
    ConcernInterpretation,
    FortuneCategory,
    FortuneGenerationError,
    OpenAIFortuneGenerator,
    SEOUL_TIMEZONE,
)


class FriendProfileInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    birth_date: date
    calendar_type: Literal["SOLAR", "LUNAR"]
    is_leap_month: bool | None = None
    birth_time: time | None = None
    gender: Literal["MALE", "FEMALE"] | None = None

    @field_validator("calendar_type", "gender", mode="before")
    @classmethod
    def normalize_choice(cls, value: object) -> object:
        return value.upper() if isinstance(value, str) else value

    @field_validator("birth_date")
    @classmethod
    def validate_birth_date(cls, value: date) -> date:
        if not 1900 <= value.year <= date.today().year:
            raise ValueError("생년월일은 1900년부터 현재 연도까지 입력할 수 있습니다.")
        return value

    @model_validator(mode="after")
    def validate_calendar_fields(self) -> "FriendProfileInput":
        if self.calendar_type == "SOLAR":
            self.is_leap_month = None
        elif self.is_leap_month is None:
            raise ValueError("음력 생년월일은 윤달 여부가 필요합니다.")
        return self


class FriendFortune(BaseModel):
    model_config = ConfigDict(extra="forbid")

    fortune_date: date
    headline: str
    total_comment: str
    total_score: int = Field(ge=0, le=100)
    lucky_number: int = Field(ge=0, le=10)
    lucky_color: str
    support_action: str

    @field_validator("headline", "total_comment", "lucky_color", "support_action")
    @classmethod
    def normalize_single_line(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("빈 문장은 사용할 수 없습니다.")
        return normalized


class FriendConcernInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: FortuneCategory
    situation: str = Field(max_length=200)

    @field_validator("situation")
    @classmethod
    def validate_situation(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("친구의 상황을 입력해 주세요.")
        return normalized


class FriendInterpretationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    profile: FriendProfileInput
    fortune: FriendFortune
    concern: FriendConcernInput


class FriendFortuneGenerator(Protocol):
    def generate_friend_fortune(
        self,
        profile: FriendProfileInput,
        fortune_date: date,
    ) -> FriendFortune: ...

    def generate_friend_interpretation(
        self,
        request: FriendInterpretationRequest,
    ) -> ConcernInterpretation: ...


class OpenAIFriendFortuneGenerator:
    FORTUNE_SCHEMA = {
        "type": "object",
        "properties": {
            "headline": {"type": "string"},
            "total_comment": {"type": "string"},
            "total_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "lucky_number": {"type": "integer", "minimum": 0, "maximum": 10},
            "lucky_color": {"type": "string"},
            "support_action": {"type": "string"},
        },
        "required": [
            "headline",
            "total_comment",
            "total_score",
            "lucky_number",
            "lucky_color",
            "support_action",
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

    def __init__(self, client: OpenAIFortuneGenerator) -> None:
        self.client = client

    def generate_friend_fortune(
        self,
        profile: FriendProfileInput,
        fortune_date: date,
    ) -> FriendFortune:
        user_data = {
            "fortune_date": fortune_date.isoformat(),
            **profile.model_dump(mode="json"),
        }
        result = self.client.request_structured_output(
            schema_name="friend_fortune",
            schema=self.FORTUNE_SCHEMA,
            instructions=(
                "너는 친구에게 선물할 한국어 오늘 운세 작성자다. 입력받은 생년월일, "
                "양력/음력, 윤달, 출생 시간, 성별과 기준 날짜를 바탕으로 운세를 작성한다. "
                "모든 문장은 친구에게 바로 보여줄 수 있는 자연스러운 한국어 한 줄로 쓴다. "
                "support_action은 오늘 친구가 해보면 좋은 응원 행동이다. 행운 색은 색 이름만 "
                "쓴다. 단정적인 사고·질병·죽음 예측이나 의료 조언은 하지 않는다."
            ),
            user_data=user_data,
        )
        try:
            return FriendFortune(fortune_date=fortune_date, **result)
        except ValidationError as error:
            raise FortuneGenerationError("친구 운세 결과 형식이 올바르지 않습니다.") from error

    def generate_friend_interpretation(
        self,
        request: FriendInterpretationRequest,
    ) -> ConcernInterpretation:
        user_data = request.model_dump(mode="json")
        result = self.client.request_structured_output(
            schema_name="friend_concern_interpretation",
            schema=self.INTERPRETATION_SCHEMA,
            instructions=(
                "너는 친구에게 전할 한국어 응원 메시지를 작성한다. 친구의 오늘 운세와 현재 "
                "상황을 연결하되 운세를 사실처럼 단정하지 않는다. day_message에는 지금 친구에게 "
                "필요한 해석을 한 줄로 쓰고, action_advice에는 친구가 실천할 구체적인 행동을 "
                "한 줄로 쓴다. 질병 진단이나 의료 조언은 하지 않는다."
            ),
            user_data=user_data,
        )
        try:
            return ConcernInterpretation.model_validate(result)
        except ValidationError as error:
            raise FortuneGenerationError("친구 고민 해석 결과 형식이 올바르지 않습니다.") from error


class FriendFortuneService:
    def __init__(
        self,
        generator: FriendFortuneGenerator,
        today_provider: Callable[[], date] | None = None,
    ) -> None:
        self.generator = generator
        self.today_provider = today_provider or self._seoul_today

    @staticmethod
    def _seoul_today() -> date:
        return datetime.now(SEOUL_TIMEZONE).date()

    def create_fortune(self, profile: FriendProfileInput) -> FriendFortune:
        return self.generator.generate_friend_fortune(profile, self.today_provider())

    def interpret(
        self,
        request: FriendInterpretationRequest,
    ) -> ConcernInterpretation:
        return self.generator.generate_friend_interpretation(request)
