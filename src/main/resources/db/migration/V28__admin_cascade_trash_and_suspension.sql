-- 최고 관리자 연쇄 휴지통의 실행 상태와 사용자·스튜디오 정지 상태를 추가한다.
ALTER TABLE users ADD COLUMN suspended_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE studios ADD COLUMN suspended_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE gallery_members ADD COLUMN deleted_at TIMESTAMP(6) WITH TIME ZONE;

CREATE INDEX idx_users_suspended_at ON users (suspended_at) WHERE suspended_at IS NOT NULL;
CREATE INDEX idx_studios_suspended_at ON studios (suspended_at) WHERE suspended_at IS NOT NULL;
CREATE INDEX idx_gallery_members_deleted_at ON gallery_members (deleted_at) WHERE deleted_at IS NOT NULL;

ALTER TABLE admin_trash_batches DROP CONSTRAINT ck_admin_trash_batch_status;
ALTER TABLE admin_trash_batches
    ADD CONSTRAINT ck_admin_trash_batch_status
        CHECK (status IN ('ACTIVE', 'PURGING', 'RESTORED', 'PURGED')),
    ADD COLUMN purge_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN purge_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN failure_code VARCHAR(120);

DROP INDEX uk_admin_trash_active_root;
CREATE UNIQUE INDEX uk_admin_trash_active_root
    ON admin_trash_batches (root_type, root_id)
    WHERE status IN ('ACTIVE', 'PURGING');

CREATE INDEX idx_admin_trash_purge_claim
    ON admin_trash_batches (status, restore_until, purge_started_at);
