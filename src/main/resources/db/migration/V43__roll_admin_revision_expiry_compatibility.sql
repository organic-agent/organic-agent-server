-- WES-258: 모든 런타임을 restore_expires_at 단일 쓰기로 옮기는 동안 구/신 이미지의
-- INSERT와 UPDATE를 함께 허용한다. 동일 이벤트 trigger는 이름순으로 실행되므로 00 sync가
-- V40 immutable guard보다 먼저 두 컬럼을 맞춘 뒤, 기존 만료 연장 금지 정책이 검증한다.

SET LOCAL lock_timeout = '15s';
LOCK TABLE admin_entity_revisions IN SHARE ROW EXCLUSIVE MODE;

CREATE OR REPLACE FUNCTION wes_sync_admin_entity_revision_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_legacy_changed BOOLEAN;
    v_restore_changed BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.expires_at IS NULL AND NEW.restore_expires_at IS NULL THEN
            RAISE EXCEPTION 'admin_entity_revision restore expiry is required';
        ELSIF NEW.expires_at IS NULL THEN
            NEW.expires_at := NEW.restore_expires_at;
        ELSIF NEW.restore_expires_at IS NULL THEN
            NEW.restore_expires_at := NEW.expires_at;
        END IF;
    ELSE
        v_legacy_changed := NEW.expires_at IS DISTINCT FROM OLD.expires_at;
        v_restore_changed := NEW.restore_expires_at IS DISTINCT FROM OLD.restore_expires_at;

        IF v_legacy_changed AND NOT v_restore_changed THEN
            NEW.restore_expires_at := NEW.expires_at;
        ELSIF v_restore_changed AND NOT v_legacy_changed THEN
            NEW.expires_at := NEW.restore_expires_at;
        ELSIF v_legacy_changed AND v_restore_changed AND
              NEW.expires_at IS DISTINCT FROM NEW.restore_expires_at THEN
            RAISE EXCEPTION 'admin_entity_revision expiry columns must match';
        END IF;
    END IF;

    IF NEW.expires_at IS NULL OR NEW.restore_expires_at IS NULL THEN
        RAISE EXCEPTION 'admin_entity_revision restore expiry is required';
    END IF;
    IF NEW.expires_at IS DISTINCT FROM NEW.restore_expires_at THEN
        RAISE EXCEPTION 'admin_entity_revision expiry columns must match';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER trg_admin_entity_revisions_sync_expiry
    ON admin_entity_revisions;

CREATE TRIGGER trg_admin_entity_revisions_00_sync_expiry
    BEFORE INSERT OR UPDATE ON admin_entity_revisions
    FOR EACH ROW
    EXECUTE FUNCTION wes_sync_admin_entity_revision_expiry();

COMMENT ON FUNCTION wes_sync_admin_entity_revision_expiry() IS
    'V43 rolling compatibility: mirror one-sided legacy/new expiry writes before immutable validation';
