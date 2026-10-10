-- Architecture v1.1: EMPTY DATABASE baseline only.
-- Apply through a versioned migration runner; never overwrite an applied file.
-- Connection PRAGMAs must run outside a transaction on EVERY new connection.
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA journal_mode = WAL;

BEGIN IMMEDIATE;

CREATE TABLE subscriptions (
    id TEXT PRIMARY KEY NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    profile_json TEXT NOT NULL
        CHECK(length(CAST(profile_json AS BLOB)) <= 65536),
    profile_version INTEGER NOT NULL DEFAULT 1 CHECK(profile_version > 0),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE runs (
    id TEXT PRIMARY KEY NOT NULL,
    subscription_id TEXT NOT NULL REFERENCES subscriptions(id) ON DELETE RESTRICT,
    status TEXT NOT NULL CHECK(status IN
        ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    phase TEXT CHECK(phase IN
        ('PREPARING', 'THINKING', 'WAITING_TOOL', 'VALIDATING', 'COMMITTING')),
    trigger_type TEXT NOT NULL CHECK(trigger_type IN ('MANUAL', 'SCHEDULED', 'RETRY')),
    schedule_date TEXT,
    mode TEXT NOT NULL CHECK(mode IN ('LIVE', 'DEMO')),
    preferences_json TEXT NOT NULL
        CHECK(length(CAST(preferences_json AS BLOB)) <= 65536),
    delivery_snapshot_json TEXT NOT NULL
        CHECK(length(CAST(delivery_snapshot_json AS BLOB)) <= 4096),
    config_snapshot_json TEXT NOT NULL
        CHECK(length(CAST(config_snapshot_json AS BLOB)) <= 16384),
    retry_of TEXT,
    idempotency_key TEXT CHECK(length(idempotency_key) <= 128),
    request_hash TEXT,
    created_at INTEGER NOT NULL,
    started_at INTEGER,
    deadline_at INTEGER NOT NULL,
    window_start INTEGER NOT NULL,
    window_end INTEGER NOT NULL CHECK(window_end >= window_start),
    finished_at INTEGER,
    error_code TEXT CHECK(length(error_code) <= 64),
    error_summary TEXT CHECK(length(error_summary) <= 512),
    model_rounds INTEGER NOT NULL DEFAULT 0 CHECK(model_rounds >= 0),
    model_attempts INTEGER NOT NULL DEFAULT 0 CHECK(model_attempts >= 0),
    tool_calls INTEGER NOT NULL DEFAULT 0 CHECK(tool_calls >= 0),
    known_input_tokens INTEGER NOT NULL DEFAULT 0 CHECK(known_input_tokens >= 0),
    known_output_tokens INTEGER NOT NULL DEFAULT 0 CHECK(known_output_tokens >= 0),
    reserved_tokens INTEGER NOT NULL DEFAULT 0 CHECK(reserved_tokens >= 0),
    unknown_usage_attempts INTEGER NOT NULL DEFAULT 0 CHECK(unknown_usage_attempts >= 0),
    network_bytes INTEGER NOT NULL DEFAULT 0 CHECK(network_bytes >= 0),
    UNIQUE(id, subscription_id, mode),
    CHECK((trigger_type = 'SCHEDULED' AND schedule_date IS NOT NULL)
       OR (trigger_type IN ('MANUAL', 'RETRY') AND schedule_date IS NULL)),
    CHECK((trigger_type = 'RETRY' AND retry_of IS NOT NULL)
       OR (trigger_type <> 'RETRY' AND retry_of IS NULL)),
    CHECK((idempotency_key IS NULL AND request_hash IS NULL)
       OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL)),
    CHECK((status IN ('QUEUED', 'RUNNING') AND finished_at IS NULL)
       OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
           AND finished_at IS NOT NULL AND phase IS NULL)),
    FOREIGN KEY(retry_of, subscription_id, mode)
        REFERENCES runs(id, subscription_id, mode) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_runs_active_subscription ON runs(subscription_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE UNIQUE INDEX ux_runs_daily ON runs(subscription_id, schedule_date)
    WHERE schedule_date IS NOT NULL;
CREATE UNIQUE INDEX ux_runs_idempotency ON runs(subscription_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_runs_subscription_created ON runs(subscription_id, created_at DESC);
CREATE INDEX ix_runs_queue ON runs(status, created_at);
CREATE INDEX ix_runs_retry_of ON runs(retry_of, subscription_id, mode);

CREATE TABLE run_sources (
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE RESTRICT,
    source_id TEXT NOT NULL,
    title TEXT NOT NULL CHECK(length(title) <= 512),
    url TEXT NOT NULL CHECK(length(url) <= 2048),
    canonical_url TEXT NOT NULL CHECK(length(canonical_url) <= 2048),
    publisher TEXT NOT NULL CHECK(length(publisher) <= 256),
    published_at INTEGER NOT NULL,
    fetched_at INTEGER NOT NULL,
    excerpt TEXT NOT NULL CHECK(length(CAST(excerpt AS BLOB)) <= 8192),
    body_ref TEXT,
    body_state TEXT NOT NULL DEFAULT 'NONE'
        CHECK(body_state IN ('NONE', 'AVAILABLE', 'EXPIRED', 'MISSING')),
    body_truncated INTEGER NOT NULL DEFAULT 0 CHECK(body_truncated IN (0, 1)),
    PRIMARY KEY(run_id, source_id),
    UNIQUE(run_id, canonical_url),
    CHECK((body_state = 'AVAILABLE' AND body_ref IS NOT NULL)
       OR (body_state <> 'AVAILABLE' AND body_ref IS NULL))
);

CREATE TABLE events (
    id INTEGER PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE RESTRICT,
    seq INTEGER NOT NULL CHECK(seq > 0),
    step_id TEXT CHECK(length(step_id) <= 64),
    event_type TEXT NOT NULL CHECK(length(event_type) <= 64),
    tool_name TEXT CHECK(length(tool_name) <= 64),
    status TEXT CHECK(length(status) <= 32),
    duration_ms INTEGER CHECK(duration_ms >= 0),
    error_code TEXT CHECK(length(error_code) <= 64),
    summary TEXT NOT NULL CHECK(length(summary) <= 512),
    payload_bytes INTEGER NOT NULL DEFAULT 0 CHECK(payload_bytes BETWEEN 0 AND 65536),
    payload_inline TEXT CHECK(length(CAST(payload_inline AS BLOB)) <= 4096),
    payload_ref TEXT CHECK(length(payload_ref) <= 256),
    payload_sha256 TEXT CHECK(length(payload_sha256) = 64),
    payload_state TEXT NOT NULL CHECK(payload_state IN
        ('NO_DETAILS', 'INLINE', 'EXTERNAL', 'OMITTED', 'EXPIRED', 'MISSING')),
    created_at INTEGER NOT NULL,
    UNIQUE(run_id, seq),
    CHECK((payload_state = 'INLINE' AND payload_inline IS NOT NULL
           AND payload_ref IS NULL
           AND payload_bytes = length(CAST(payload_inline AS BLOB)))
       OR (payload_state = 'EXTERNAL' AND payload_inline IS NULL
           AND payload_ref IS NOT NULL AND payload_sha256 IS NOT NULL
           AND payload_bytes > 0)
       OR (payload_state IN ('NO_DETAILS', 'OMITTED', 'EXPIRED', 'MISSING')
           AND payload_inline IS NULL AND payload_ref IS NULL))
);
CREATE INDEX ix_events_run_step ON events(run_id, step_id);

-- Defense in depth; reserve final-event capacity in the application.
CREATE TRIGGER trg_events_count BEFORE INSERT ON events
WHEN (SELECT count(*) FROM events WHERE run_id = NEW.run_id) >= 256
BEGIN
    SELECT RAISE(ABORT, 'EVENT_COUNT_LIMIT');
END;

CREATE TRIGGER trg_sources_count BEFORE INSERT ON run_sources
WHEN (SELECT count(*) FROM run_sources WHERE run_id = NEW.run_id) >= 100
BEGIN
    SELECT RAISE(ABORT, 'SOURCE_COUNT_LIMIT');
END;

CREATE TABLE briefs (
    id TEXT PRIMARY KEY NOT NULL,
    run_id TEXT NOT NULL UNIQUE,
    subscription_id TEXT NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('LIVE', 'DEMO')),
    title TEXT NOT NULL CHECK(length(title) <= 200),
    intro TEXT NOT NULL CHECK(length(intro) <= 2000),
    items_json TEXT NOT NULL CHECK(length(CAST(items_json AS BLOB)) <= 65536),
    empty_brief INTEGER NOT NULL DEFAULT 0 CHECK(empty_brief IN (0, 1)),
    created_at INTEGER NOT NULL,
    FOREIGN KEY(run_id, subscription_id, mode)
        REFERENCES runs(id, subscription_id, mode) ON DELETE RESTRICT
);
CREATE INDEX ix_briefs_history ON briefs(subscription_id, mode, created_at DESC);

CREATE TABLE deliveries (
    id TEXT PRIMARY KEY NOT NULL,
    brief_id TEXT NOT NULL REFERENCES briefs(id) ON DELETE RESTRICT,
    channel TEXT NOT NULL CHECK(channel = 'EMAIL'),
    recipient TEXT NOT NULL CHECK(length(recipient) BETWEEN 3 AND 320),
    status TEXT NOT NULL CHECK(status IN
        ('PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN', 'CANCELLED')),
    message_id TEXT NOT NULL UNIQUE CHECK(length(message_id) <= 256),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 3),
    retry_cycle INTEGER NOT NULL DEFAULT 0 CHECK(retry_cycle >= 0),
    next_attempt_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    sent_at INTEGER,
    error_code TEXT CHECK(length(error_code) <= 64),
    error_summary TEXT CHECK(length(error_summary) <= 512),
    UNIQUE(brief_id, channel),
    CHECK((status = 'SENT' AND sent_at IS NOT NULL)
       OR (status <> 'SENT' AND sent_at IS NULL))
);
CREATE INDEX ix_deliveries_due ON deliveries(status, next_attempt_at);

-- No external delivery may be created or reassigned for demo data.
CREATE TRIGGER trg_deliveries_live_insert BEFORE INSERT ON deliveries
WHEN (SELECT mode FROM briefs WHERE id = NEW.brief_id) = 'DEMO'
BEGIN
    SELECT RAISE(ABORT, 'DEMO_EXTERNAL_DELIVERY_FORBIDDEN');
END;
CREATE TRIGGER trg_deliveries_live_update BEFORE UPDATE OF brief_id ON deliveries
WHEN (SELECT mode FROM briefs WHERE id = NEW.brief_id) = 'DEMO'
BEGIN
    SELECT RAISE(ABORT, 'DEMO_EXTERNAL_DELIVERY_FORBIDDEN');
END;

COMMIT;
PRAGMA foreign_key_check;

-- Times are INTEGER UTC epoch milliseconds; schedule_date is local YYYY-MM-DD.
-- Application checks JSON/schema, lifecycle transitions, aggregate byte quotas,
-- request_hash equality, source/date policies, recipient snapshot and authorization.
