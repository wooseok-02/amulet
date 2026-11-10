from functools import lru_cache
from typing import Literal

from pydantic import HttpUrl, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "부꾸 API"
    app_env: Literal["development", "test", "production"] = "development"
    debug: bool = False
    supabase_url: HttpUrl | None = None
    supabase_publishable_key: SecretStr | None = None
    openai_api_key: SecretStr | None = None
    openai_model: str = "gpt-6-luna"
    amulet_share_base_url: HttpUrl | None = None
    amulet_apk_download_url: HttpUrl | None = None

    @field_validator("amulet_share_base_url", "amulet_apk_download_url", mode="before")
    @classmethod
    def empty_amulet_url_is_unconfigured(cls, value):
        return None if isinstance(value, str) and not value.strip() else value

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()

