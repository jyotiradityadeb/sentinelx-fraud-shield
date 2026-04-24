from __future__ import annotations

from pydantic import ConfigDict
from pydantic import BaseModel, Field


class Event(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    ts: int
    session_id: str = Field(alias="sessionId")
    user_id: str = Field(alias="userId")
    event_type: str = Field(alias="eventType")
    package_name: str = Field(default="", alias="packageName")
    screen_name: str = Field(default="", alias="screenName")
    call_state: str = Field(default="IDLE", alias="callState")
    caller_trust: str = Field(default="UNKNOWN", alias="callerTrust")
    interaction_type: str | None = Field(default="", alias="interactionType")
    dwell_ms: int = Field(default=0, alias="dwellMs")
    voice_stress_score: float = Field(default=0.0, ge=0.0, le=1.0, alias="voiceStressScore")
    network_threat_score: int = Field(default=0, ge=0, le=15, alias="networkThreatScore")
    geo_hash: str = Field(default="", alias="geoHash")


class EventBatch(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    session_id: str = Field(alias="sessionId")
    user_id: str = Field(alias="userId")
    events: list[Event]


class SessionPayload(BaseModel):
    session_id: str
    user_id: str
    seconds_since_call: int = Field(default=9999, ge=0)
    switch_count_20s: int = Field(default=0, ge=0)
    confirm_dwell_ms: int = Field(default=14000, ge=0)
    tap_density: float = Field(default=1.0, ge=0.0)
    revisit_count: int = Field(default=0, ge=0)
    session_duration_ms: int = Field(default=30000, ge=0)
    caller_trust: str = "UNKNOWN"
    is_messaging_before: bool = False
    is_whatsapp_voip: bool = False
    voice_stress_score: float = Field(default=0.0, ge=0.0, le=1.0)
    network_threat_score: int = Field(default=0, ge=0, le=15)
    geo_hash: str = "tdr1j"


class ThreatLookup(BaseModel):
    number_hash: str


class GuardianNotify(BaseModel):
    user_id: str
    llm_summary: str
    score: int
    threat_type: str


class RiskResult(BaseModel):
    session_id: str
    total_score: int
    label: str
    threat_type: str = ""
    triggered_signals: list[dict] = Field(default_factory=list)
    sig_caller_trust: int = 0
    sig_transition: int = 0
    sig_confirm_press: int = 0
    sig_behavioral: int = 0
    sig_voice_stress: int = 0
    sig_network: int = 0
    anomaly_score: float = 0.0
