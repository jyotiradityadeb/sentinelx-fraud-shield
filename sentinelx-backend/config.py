from __future__ import annotations

import os

from dotenv import load_dotenv

load_dotenv()


def _get_required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ValueError(f"Missing required environment variable: {name}")
    if "placeholder" in value.lower():
        raise ValueError(f"Environment variable {name} still contains a placeholder value")
    return value


SUPABASE_URL: str = _get_required_env("SUPABASE_URL")
SUPABASE_KEY: str = _get_required_env("SUPABASE_KEY")
OPENAI_KEY: str = _get_required_env("OPENAI_KEY")
HMAC_SECRET: str = _get_required_env("HMAC_SECRET")

PORT: int = int(os.getenv("PORT", "8000"))
DEBUG: bool = os.getenv("DEBUG", "true").strip().lower() in {"1", "true", "yes", "on"}

print("Config loaded")
