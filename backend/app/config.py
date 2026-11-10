from functools import lru_cache
from pathlib import Path
import re
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
    amulet_apk_file_path: Path | None = None
    android_package_name: str | None = None
    android_sha256_cert_fingerprints: list[str] = []

    @field_validator("android_sha256_cert_fingerprints")
    @classmethod
    def validate_android_fingerprints(cls, values):
        if any(not re.fullmatch(r"(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}", value) for value in values):
            raise ValueError("Android 서명은 콜론으로 구분한 SHA-256 지문이어야 합니다.")
        return [value.upper() for value in values]


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

