CREATE TABLE IF NOT EXISTS group_ping_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id TEXT NOT NULL,
    address TEXT NOT NULL,
    input_index INTEGER NOT NULL,
    completion_index INTEGER NOT NULL,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL,
    success_count INTEGER NOT NULL,
    average_delay_ms INTEGER,
    packet_loss_percent INTEGER NOT NULL,
    error TEXT,
    UNIQUE(job_id, address)
);
CREATE INDEX IF NOT EXISTS idx_group_ping_results_job ON group_ping_results(job_id);
