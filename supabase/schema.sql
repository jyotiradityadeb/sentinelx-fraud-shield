-- SentinelX v2 - Database Schema (Phase 0 Real Device)
-- No guardians table: guardian info stored on-device via SharedPreferences.

CREATE TABLE IF NOT EXISTS sessions (
  id                uuid        DEFAULT gen_random_uuid() PRIMARY KEY,
  created_at        timestamptz DEFAULT now() NOT NULL,
  user_id           text        NOT NULL,
  score             integer     NOT NULL DEFAULT 0,
  label             text        NOT NULL DEFAULT 'SAFE',
  threat_type       text,
  caller_trust      text,
  geo_hash          text,
  llm_summary       text,
  llm_user_prompt   text,
  guardian_sms_sent boolean     DEFAULT false,
  sig_caller_trust  integer     DEFAULT 0,
  sig_transition    integer     DEFAULT 0,
  sig_confirm_press integer     DEFAULT 0,
  sig_behavioral    integer     DEFAULT 0,
  sig_voice_stress  integer     DEFAULT 0,
  sig_network       integer     DEFAULT 0,
  triggered_signals jsonb       DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS events (
  id                   bigserial   PRIMARY KEY,
  session_id           uuid        REFERENCES sessions(id) ON DELETE CASCADE,
  ts                   bigint      NOT NULL,
  event_type           text        NOT NULL,
  package_name         text,
  screen_name          text,
  call_state           text,
  caller_trust         text,
  interaction_type     text,
  dwell_ms             integer     DEFAULT 0,
  voice_stress_score   float       DEFAULT 0.0,
  network_threat_score integer     DEFAULT 0,
  geo_hash             text
);

CREATE TABLE IF NOT EXISTS threat_network (
  number_hash   text        PRIMARY KEY,
  report_count  integer     NOT NULL DEFAULT 1,
  first_seen    timestamptz DEFAULT now(),
  last_seen     timestamptz DEFAULT now(),
  threat_types  text[]      DEFAULT '{}',
  avg_score     float       DEFAULT 0.0
);

CREATE TABLE IF NOT EXISTS guardians (
  id         uuid        DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id    text        NOT NULL,
  name       text        NOT NULL DEFAULT 'Guardian',
  phone      text        NOT NULL,
  created_at timestamptz DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS user_baselines (
  user_id             text    PRIMARY KEY,
  updated_at          timestamptz DEFAULT now(),
  avg_confirm_dwell   float   DEFAULT 14000,
  avg_session_dur     float   DEFAULT 45000,
  avg_switch_count    float   DEFAULT 1.5,
  avg_tap_density     float   DEFAULT 1.2,
  avg_voice_stress    float   DEFAULT 0.1,
  session_count       integer DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_created ON sessions(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_label ON sessions(label);
CREATE INDEX IF NOT EXISTS idx_events_session ON events(session_id);
CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts DESC);
CREATE INDEX IF NOT EXISTS idx_threat_hash ON threat_network(number_hash);
CREATE INDEX IF NOT EXISTS idx_guardians_user ON guardians(user_id);

ALTER TABLE sessions REPLICA IDENTITY FULL;

INSERT INTO user_baselines (user_id)
VALUES ('device_001')
ON CONFLICT (user_id) DO NOTHING;
