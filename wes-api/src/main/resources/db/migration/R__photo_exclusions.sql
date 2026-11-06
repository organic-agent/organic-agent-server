-- main과 develop의 번호형 마이그레이션 순서를 유지한다. 재실행해도 제외 상태와 변경 번호를 보존한다.
-- 셀렉 제외는 원본 삭제가 아니다. 폴더·공유 연결과 업로드 용량은 보존한다.
ALTER TABLE photos ADD COLUMN IF NOT EXISTS excluded_at TIMESTAMPTZ;
ALTER TABLE photos ADD COLUMN IF NOT EXISTS excluded_by BIGINT;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_photos_exclusion' AND conrelid = 'photos'::regclass) THEN
        ALTER TABLE photos ADD CONSTRAINT ck_photos_exclusion
            CHECK ((excluded_at IS NULL) = (excluded_by IS NULL));
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_photos_visible_gallery ON photos (gallery_id, display_order, id)
    WHERE deleted_at IS NULL AND excluded_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_photos_excluded_gallery ON photos (gallery_id, display_order, id)
    WHERE deleted_at IS NULL AND excluded_at IS NOT NULL;

-- 최초 설치에서는 반복 마이그레이션이 뒤에 실행된다.
CREATE TABLE IF NOT EXISTS gallery_selection_sync (
    gallery_id BIGINT PRIMARY KEY REFERENCES galleries(id) ON DELETE CASCADE,
    selection_revision BIGINT NOT NULL DEFAULT 0,
    rating_revision BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE gallery_selection_sync ADD COLUMN IF NOT EXISTS exclusion_revision BIGINT NOT NULL DEFAULT 0;

CREATE OR REPLACE FUNCTION track_photo_exclusion_sync() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    gid BIGINT;
    changed BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        gid := OLD.gallery_id;
        changed := OLD.excluded_at IS NOT NULL AND OLD.deleted_at IS NULL;
    ELSE
        gid := NEW.gallery_id;
        changed := NEW.excluded_at IS DISTINCT FROM OLD.excluded_at
            OR (NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
                AND (NEW.excluded_at IS NOT NULL OR OLD.excluded_at IS NOT NULL));
    END IF;
    IF changed THEN
        INSERT INTO gallery_selection_sync (gallery_id, exclusion_revision)
        SELECT id, 1 FROM galleries WHERE id = gid
        ON CONFLICT (gallery_id) DO UPDATE SET
            exclusion_revision = gallery_selection_sync.exclusion_revision + 1;
        PERFORM bump_gallery_selection_sync(gid, 0, 1);
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE TRIGGER photos_exclusion_sync AFTER UPDATE OF excluded_at, deleted_at OR DELETE ON photos
FOR EACH ROW EXECUTE FUNCTION track_photo_exclusion_sync();
