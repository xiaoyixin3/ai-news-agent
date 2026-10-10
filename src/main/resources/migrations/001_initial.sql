PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS subscriptions (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    profile TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS runs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES subscriptions(id),
    status TEXT NOT NULL CHECK(status IN ('queued','running','succeeded','failed')),
    trigger TEXT NOT NULL,
    schedule_date TEXT,
    mode TEXT NOT NULL,
    created_at TEXT NOT NULL,
    finished_at TEXT,
    error TEXT,
    preferences TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS daily_once ON runs(user_id, schedule_date)
    WHERE schedule_date IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS one_active_run ON runs(user_id)
    WHERE status IN ('queued','running');
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL REFERENCES runs(id),
    created_at TEXT NOT NULL,
    kind TEXT NOT NULL,
    payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS briefs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES subscriptions(id),
    run_id TEXT NOT NULL UNIQUE REFERENCES runs(id),
    title TEXT NOT NULL,
    intro TEXT NOT NULL,
    items TEXT NOT NULL,
    mode TEXT NOT NULL,
    created_at TEXT NOT NULL,
    delivery_status TEXT NOT NULL DEFAULT 'inbox',
    delivery_error TEXT
);
PRAGMA user_version=1;
CREATE INDEX IF NOT EXISTS runs_history ON runs(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS briefs_history ON briefs(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS events_run ON events(run_id, id);
