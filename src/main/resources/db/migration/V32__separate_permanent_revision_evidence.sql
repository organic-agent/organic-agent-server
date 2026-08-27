-- WES-258: sanitized before/after는 영구 감사 증거이고, 원문 복원 payload만 7일 한정이다.
--
-- 첫 모듈 분리 배포는 새 공개 API가 migration을 끝낸 뒤에도 직전 모놀리스 이미지로
-- rollback할 수 있어야 한다. 따라서 expires_at을 rename하지 않고 새 이름을 expand하며,
-- 두 세대 애플리케이션 중 어느 쪽이 INSERT해도 두 컬럼이 같은 값을 갖게 한다.
ALTER TABLE admin_entity_revisions
    ADD COLUMN restore_expires_at TIMESTAMP WITH TIME ZONE;

UPDATE admin_entity_revisions
SET restore_expires_at = expires_at;

ALTER TABLE admin_entity_revisions
    ALTER COLUMN restore_expires_at SET NOT NULL;

CREATE OR REPLACE FUNCTION wes_sync_admin_entity_revision_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.expires_at IS NULL AND NEW.restore_expires_at IS NULL THEN
        RAISE EXCEPTION 'admin_entity_revision restore expiry is required';
    ELSIF NEW.expires_at IS NULL THEN
        NEW.expires_at := NEW.restore_expires_at;
    ELSIF NEW.restore_expires_at IS NULL THEN
        NEW.restore_expires_at := NEW.expires_at;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_admin_entity_revisions_sync_expiry
    BEFORE INSERT ON admin_entity_revisions
    FOR EACH ROW
    EXECUTE FUNCTION wes_sync_admin_entity_revision_expiry();

ALTER TABLE admin_entity_revisions
    ADD CONSTRAINT ck_admin_entity_revisions_expiry_columns_match
    CHECK (expires_at = restore_expires_at);

CREATE INDEX idx_admin_entity_revisions_restore_expires_at
    ON admin_entity_revisions (restore_expires_at)
    WHERE before_restore_payload IS NOT NULL OR after_restore_payload IS NOT NULL;

COMMENT ON COLUMN admin_entity_revisions.expires_at IS
    '전환기 구 애플리케이션 호환 컬럼. restore_expires_at과 항상 같은 값이며 후속 cleanup migration에서 제거한다.';
COMMENT ON COLUMN admin_entity_revisions.restore_expires_at IS
    'before/after 영구 snapshot의 만료가 아니라 private restore payload의 만료 시각';
COMMENT ON COLUMN admin_entity_revisions.before_restore_payload IS
    'API 미노출 7일 한정 복원 payload. 만료 시 row는 유지하고 이 컬럼만 NULL 처리한다.';
COMMENT ON COLUMN admin_entity_revisions.after_restore_payload IS
    'API 미노출 7일 한정 복원 payload. 만료 시 row는 유지하고 이 컬럼만 NULL 처리한다.';
