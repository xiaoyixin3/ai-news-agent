-- v1.1 target schema. MigrationRunner maps existing v1.0 rows transactionally.
-- API status/mode remain lower case. UTC timestamps use ISO-8601; *_at_ms and
-- window/deadline fields explicitly use epoch milliseconds.
CREATE TABLE subscriptions (
 id TEXT PRIMARY KEY NOT NULL, token_hash TEXT NOT NULL UNIQUE,
 profile TEXT NOT NULL CHECK(length(CAST(profile AS BLOB))<=65536),
 profile_version INTEGER NOT NULL DEFAULT 1, enabled INTEGER NOT NULL DEFAULT 1,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE runs (
 id TEXT PRIMARY KEY NOT NULL, subscription_id TEXT NOT NULL REFERENCES subscriptions(id) ON DELETE RESTRICT,
 status TEXT NOT NULL CHECK(status IN ('queued','running','succeeded','failed','cancelled')),
 phase TEXT, trigger TEXT NOT NULL CHECK(trigger IN ('manual','scheduled','retry')),
 schedule_date TEXT, mode TEXT NOT NULL CHECK(mode IN ('demo','live')),
 preferences TEXT NOT NULL CHECK(length(CAST(preferences AS BLOB))<=65536),
 delivery_snapshot TEXT NOT NULL, config_snapshot TEXT NOT NULL,
 retry_of TEXT, idempotency_key TEXT, request_hash TEXT,
 created_at TEXT NOT NULL, started_at TEXT, deadline_at_ms INTEGER NOT NULL,
 window_start_ms INTEGER NOT NULL, window_end_ms INTEGER NOT NULL,
 finished_at TEXT, error TEXT, error_code TEXT,
 model_rounds INTEGER NOT NULL DEFAULT 0, model_attempts INTEGER NOT NULL DEFAULT 0,
 tool_calls INTEGER NOT NULL DEFAULT 0, known_input_tokens INTEGER NOT NULL DEFAULT 0,
 known_output_tokens INTEGER NOT NULL DEFAULT 0, reserved_tokens INTEGER NOT NULL DEFAULT 0,
 unknown_usage_attempts INTEGER NOT NULL DEFAULT 0, network_bytes INTEGER NOT NULL DEFAULT 0,
 UNIQUE(id,subscription_id,mode),
 FOREIGN KEY(retry_of,subscription_id,mode) REFERENCES runs(id,subscription_id,mode) ON DELETE RESTRICT,
 CHECK(window_end_ms>=window_start_ms),
 CHECK((trigger='scheduled' AND schedule_date IS NOT NULL) OR (trigger IN ('manual','retry') AND schedule_date IS NULL)),
 CHECK((trigger='retry' AND retry_of IS NOT NULL) OR (trigger<>'retry' AND retry_of IS NULL)),
 CHECK((idempotency_key IS NULL AND request_hash IS NULL) OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
);
CREATE UNIQUE INDEX ux_runtime_active ON runs(subscription_id) WHERE status IN ('queued','running');
CREATE UNIQUE INDEX ux_runtime_daily ON runs(subscription_id,schedule_date) WHERE schedule_date IS NOT NULL;
CREATE UNIQUE INDEX ux_runtime_idempotency ON runs(subscription_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_runtime_history ON runs(subscription_id,created_at DESC);
CREATE TABLE run_sources (
 run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE RESTRICT, source_id TEXT NOT NULL,
 title TEXT NOT NULL, url TEXT NOT NULL, canonical_url TEXT NOT NULL, publisher TEXT NOT NULL,
 published_at TEXT NOT NULL, fetched_at TEXT NOT NULL, excerpt TEXT NOT NULL,
 body_ref TEXT, body_state TEXT NOT NULL DEFAULT 'NONE', body_truncated INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(run_id,source_id), UNIQUE(run_id,canonical_url),
 CHECK(body_state IN ('NONE','AVAILABLE','EXPIRED','MISSING')),
 CHECK((body_state='AVAILABLE' AND body_ref IS NOT NULL) OR (body_state<>'AVAILABLE' AND body_ref IS NULL))
);
CREATE TABLE events (
 id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE RESTRICT,
 created_at TEXT NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL, step_id TEXT, tool_name TEXT,
 status TEXT, duration_ms INTEGER, error_code TEXT, summary TEXT NOT NULL,
 payload_bytes INTEGER NOT NULL, payload TEXT, payload_ref TEXT, payload_sha256 TEXT,
 payload_state TEXT NOT NULL CHECK(payload_state IN ('INLINE','EXTERNAL','OMITTED','EXPIRED','MISSING')),
 UNIQUE(run_id,seq), CHECK(payload IS NULL OR length(CAST(payload AS BLOB))<=4096),
 CHECK(payload_bytes<=65536)
);
CREATE INDEX ix_runtime_events ON events(run_id,seq);
CREATE TABLE briefs (
 id TEXT PRIMARY KEY NOT NULL, subscription_id TEXT NOT NULL, run_id TEXT NOT NULL UNIQUE,
 title TEXT NOT NULL CHECK(length(title)<=200), intro TEXT NOT NULL CHECK(length(intro)<=2000),
 items TEXT NOT NULL CHECK(length(CAST(items AS BLOB))<=65536), mode TEXT NOT NULL,
 empty_brief INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL,
 FOREIGN KEY(run_id,subscription_id,mode) REFERENCES runs(id,subscription_id,mode) ON DELETE RESTRICT
);
CREATE INDEX ix_runtime_briefs ON briefs(subscription_id,mode,created_at DESC);
CREATE TABLE deliveries (
 id TEXT PRIMARY KEY NOT NULL, brief_id TEXT NOT NULL UNIQUE REFERENCES briefs(id) ON DELETE RESTRICT,
 channel TEXT NOT NULL CHECK(channel='email'), recipient TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('pending','sending','sent','failed','unknown','cancelled')),
 message_id TEXT NOT NULL UNIQUE, attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 3),
 retry_cycle INTEGER NOT NULL DEFAULT 0, next_attempt_at_ms INTEGER,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, sent_at TEXT, error_code TEXT, error_summary TEXT,
 CHECK((status='sent' AND sent_at IS NOT NULL) OR (status<>'sent' AND sent_at IS NULL))
);
CREATE INDEX ix_runtime_delivery_due ON deliveries(status,next_attempt_at_ms);
