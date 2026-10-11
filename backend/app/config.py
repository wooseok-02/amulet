from functools import lru_cache
from typing import Literal

from pydantic import HttpUrl, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "부꾸 API"
    app_env: Literal["development", "test", "production"] = "development"
    debug: bool = False
    supabase_url: HttpUrl | None = None
    supabase_publishable_key: SecretStr | None = None
    openai_api_key: SecretStr | None = None
    openai_model: str = "gpt-6-luna"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()

