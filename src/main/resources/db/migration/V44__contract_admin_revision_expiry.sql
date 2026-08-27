-- WES-258: 모든 런타임이 restore_expires_at 단일 계약으로 전환된 뒤 V32/V43의
-- expand-contract 호환 계층을 제거한다. Flyway의 PostgreSQL transaction 안에서
-- fail-closed 검증과 DDL을 함께 수행해 부분 적용을 허용하지 않는다.

SET LOCAL lock_timeout = '15s';
LOCK TABLE admin_entity_revisions IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM admin_entity_revisions
        WHERE expires_at IS DISTINCT FROM restore_expires_at
    ) THEN
        RAISE EXCEPTION
            'cannot contract admin_entity_revisions: expiry columns do not match';
    END IF;
END;
$$;

-- 기존 immutable trigger가 참조하는 함수 OID를 유지한 채 legacy column 의존만 제거한다.
CREATE OR REPLACE FUNCTION prevent_admin_entity_revision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'admin_entity_revisions are immutable';
    END IF;
    IF ROW(
        NEW.id, NEW.target_type, NEW.target_id, NEW.revision_number, NEW.operation,
        NEW.before_snapshot, NEW.after_snapshot, NEW.snapshot_schema_version,
        NEW.target_version, NEW.version, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.id, OLD.target_type, OLD.target_id, OLD.revision_number, OLD.operation,
        OLD.before_snapshot, OLD.after_snapshot, OLD.snapshot_schema_version,
        OLD.target_version, OLD.version, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'permanent admin_entity_revision fields are immutable';
    END IF;
    IF NEW.restore_expires_at IS NULL OR
       NEW.restore_expires_at > OLD.restore_expires_at THEN
        RAISE EXCEPTION 'admin_entity_revision restore expiry may only be shortened';
    END IF;
    IF NOT (
        NEW.before_restore_payload IS NOT DISTINCT FROM OLD.before_restore_payload OR
        (OLD.before_restore_payload IS NOT NULL AND NEW.before_restore_payload IS NULL)
    ) OR NOT (
        NEW.after_restore_payload IS NOT DISTINCT FROM OLD.after_restore_payload OR
        (OLD.after_restore_payload IS NOT NULL AND NEW.after_restore_payload IS NULL)
    ) THEN
        RAISE EXCEPTION 'admin_entity_revision restore payload may only be cleared';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER trg_admin_entity_revisions_00_sync_expiry
    ON admin_entity_revisions;
DROP FUNCTION wes_sync_admin_entity_revision_expiry();

ALTER TABLE admin_entity_revisions
    DROP CONSTRAINT ck_admin_entity_revisions_expiry_columns_match;
DROP INDEX idx_admin_entity_revisions_expires_at;
ALTER TABLE admin_entity_revisions
    DROP COLUMN expires_at;

ALTER TABLE admin_entity_revisions
    ALTER COLUMN restore_expires_at SET NOT NULL;
ALTER TABLE admin_entity_revisions
    VALIDATE CONSTRAINT ck_admin_entity_revisions_restore_window;

COMMENT ON COLUMN admin_entity_revisions.restore_expires_at IS
    'API 미노출 private restore payload의 유일한 만료 시각. 생성 후 최대 7일이며 연장할 수 없다.';
