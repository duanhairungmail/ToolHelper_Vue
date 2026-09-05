CREATE TABLE task_runs (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    completed_at TEXT,
    row_count INTEGER NOT NULL DEFAULT 0,
    error_code TEXT,
    trace_id TEXT NOT NULL
);

CREATE TABLE group_ping_runs (
    id TEXT PRIMARY KEY,
    input TEXT NOT NULL,
    status TEXT NOT NULL,
    target_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    completed_at TEXT,
    trace_id TEXT NOT NULL
);

CREATE TABLE audit_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT,
    operation_type TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    row_count INTEGER NOT NULL DEFAULT 0,
    result_code TEXT NOT NULL,
    created_at TEXT NOT NULL,
    trace_id TEXT NOT NULL
);

CREATE TABLE integration_status (
    name TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    version TEXT,
    updated_at TEXT NOT NULL,
    trace_id TEXT NOT NULL
);

CREATE INDEX idx_audit_events_created_at ON audit_events(created_at);
