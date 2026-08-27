-- A failed purge must not be selected again immediately: otherwise the oldest poison rows keep
-- occupying every bounded claim batch and permanently starve later eligible data.  Retry timing is
-- durable, and repeated failures eventually move to an operator-visible dead-letter status.

ALTER TABLE admin_trash_batches
    DROP CONSTRAINT ck_admin_trash_batch_status,
    ADD COLUMN next_purge_attempt_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE admin_trash_batches
    ADD CONSTRAINT ck_admin_trash_batch_status
        CHECK (status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED', 'RESTORED', 'PURGED'));

DROP INDEX idx_admin_trash_purge_claim;
CREATE INDEX idx_admin_trash_purge_claim
    ON admin_trash_batches (status, next_purge_attempt_at, restore_until, purge_started_at);

DROP INDEX uk_admin_trash_active_root;
CREATE UNIQUE INDEX uk_admin_trash_active_root
    ON admin_trash_batches (root_type, root_id)
    WHERE status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED');

ALTER TABLE admin_child_trash_records
    DROP CONSTRAINT ck_admin_child_trash_status,
    ADD COLUMN next_purge_attempt_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE admin_child_trash_records
    ADD CONSTRAINT ck_admin_child_trash_status
        CHECK (status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED', 'RESTORED', 'PURGED'));

DROP INDEX idx_admin_child_trash_purge_claim;
CREATE INDEX idx_admin_child_trash_purge_claim
    ON admin_child_trash_records (status, next_purge_attempt_at, restore_until, purge_started_at);

DROP INDEX uk_admin_child_trash_active_resource;
CREATE UNIQUE INDEX uk_admin_child_trash_active_resource
    ON admin_child_trash_records (resource_type, resource_id)
    WHERE status IN ('ACTIVE', 'PURGING', 'PURGE_FAILED');

COMMENT ON COLUMN admin_trash_batches.next_purge_attempt_at IS
    '최근 purge 실패 뒤 지수 backoff가 끝나는 시각. PURGE_FAILED는 운영자 조치 전 자동 재시도하지 않는다.';
COMMENT ON COLUMN admin_child_trash_records.next_purge_attempt_at IS
    '최근 purge 실패 뒤 지수 backoff가 끝나는 시각. PURGE_FAILED는 운영자 조치 전 자동 재시도하지 않는다.';
