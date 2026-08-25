ALTER TABLE admin_idempotency_keys
    ADD COLUMN target_type VARCHAR(40),
    ADD COLUMN target_id VARCHAR(100),
    ADD COLUMN correlation_id VARCHAR(32),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 1;

CREATE INDEX idx_admin_idempotency_target
    ON admin_idempotency_keys (target_type, target_id, created_at DESC);
CREATE INDEX idx_admin_idempotency_correlation
    ON admin_idempotency_keys (correlation_id)
    WHERE correlation_id IS NOT NULL;
