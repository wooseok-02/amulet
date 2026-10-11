from dataclasses import dataclass

from fastapi import APIRouter, Depends, HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from supabase import AuthError, Client

from app.config import Settings, get_settings
from app.fortune import (
    ConcernInput,
    ConcernInterpretation,
    DailyFortune,
    FortuneError,
    FortuneGenerationError,
    FortuneService,
    OpenAIFortuneGenerator,
    ProfileNotFoundError,
    SupabaseFortuneRepository,
)
from app.friend_fortune import (
    FriendFortune,
    FriendFortuneService,
    FriendInterpretationRequest,
    FriendProfileInput,
    OpenAIFriendFortuneGenerator,
)
from app.supabase import get_supabase_client


router = APIRouter(prefix="/fortune", tags=["fortune"])
bearer_scheme = HTTPBearer(auto_error=False)

AUTH_REQUIRED_MESSAGE = "로그인이 필요합니다."
INVALID_ACCESS_TOKEN_MESSAGE = "로그인 정보가 만료되었습니다. 다시 로그인해 주세요."


@dataclass(frozen=True)
class AuthenticatedSupabase:
    user_id: str
    client: Client


@dataclass(frozen=True)
class FortuneRequestContext:
    user_id: str
    service: FortuneService


def get_authenticated_supabase(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer_scheme),
    client: Client = Depends(get_supabase_client),
) -> AuthenticatedSupabase:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=AUTH_REQUIRED_MESSAGE,
        )

    token = credentials.credentials
    try:
        response = client.auth.get_user(token)
    except AuthError as error:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=INVALID_ACCESS_TOKEN_MESSAGE,
        ) from error

    if response is None or response.user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=INVALID_ACCESS_TOKEN_MESSAGE,
        )

    client.postgrest.auth(token)
    return AuthenticatedSupabase(user_id=response.user.id, client=client)


def get_fortune_request_context(
    auth: AuthenticatedSupabase = Depends(get_authenticated_supabase),
    settings: Settings = Depends(get_settings),
) -> FortuneRequestContext:
    if settings.openai_api_key is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="운세 생성 서비스가 설정되지 않았습니다.",
        )

    repository = SupabaseFortuneRepository(auth.client)
    generator = OpenAIFortuneGenerator(
        api_key=settings.openai_api_key.get_secret_value(),
        model=settings.openai_model,
    )
    return FortuneRequestContext(
        user_id=auth.user_id,
        service=FortuneService(repository, generator),
    )


def get_friend_fortune_service(
    auth: AuthenticatedSupabase = Depends(get_authenticated_supabase),
    settings: Settings = Depends(get_settings),
) -> FriendFortuneService:
    del auth
    if settings.openai_api_key is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="운세 생성 서비스가 설정되지 않았습니다.",
        )

    openai_client = OpenAIFortuneGenerator(
        api_key=settings.openai_api_key.get_secret_value(),
        model=settings.openai_model,
    )
    return FriendFortuneService(OpenAIFriendFortuneGenerator(openai_client))


def handle_fortune_error(error: FortuneError) -> None:
    if isinstance(error, ProfileNotFoundError):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(error),
        ) from error
    if isinstance(error, FortuneGenerationError):
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="운세 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.",
        ) from error
    raise HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail="운세 정보를 불러오지 못했습니다.",
    ) from error


@router.get("/today", response_model=DailyFortune)
def get_today_fortune(
    context: FortuneRequestContext = Depends(get_fortune_request_context),
) -> DailyFortune:
    try:
        return context.service.get_daily_fortune(context.user_id)
    except FortuneError as error:
        handle_fortune_error(error)


@router.post("/interpret", response_model=ConcernInterpretation)
def interpret_concern(
    request: ConcernInput,
    context: FortuneRequestContext = Depends(get_fortune_request_context),
) -> ConcernInterpretation:
    try:
        return context.service.interpret_concern(context.user_id, request)
    except FortuneError as error:
        handle_fortune_error(error)


@router.post("/friend/today", response_model=FriendFortune)
def create_friend_fortune(
    request: FriendProfileInput,
    service: FriendFortuneService = Depends(get_friend_fortune_service),
) -> FriendFortune:
    try:
        return service.create_fortune(request)
    except FortuneError as error:
        handle_fortune_error(error)


@router.post("/friend/interpret", response_model=ConcernInterpretation)
def interpret_friend_concern(
    request: FriendInterpretationRequest,
    service: FriendFortuneService = Depends(get_friend_fortune_service),
) -> ConcernInterpretation:
    try:
        return service.interpret(request)
    except FortuneError as error:
        handle_fortune_error(error)
