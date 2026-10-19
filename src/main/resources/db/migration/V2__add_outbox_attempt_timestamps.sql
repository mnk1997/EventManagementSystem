ALTER TABLE outbox_job
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX ix_outbox_expired_leases ON outbox_job (locked_until)
    WHERE status = 'PROCESSING';
