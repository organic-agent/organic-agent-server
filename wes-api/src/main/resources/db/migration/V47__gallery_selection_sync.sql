-- 선택·별점의 현재 변경 번호만 보관한다. 삭제도 감지해야 하므로 MAX(updated_at)을 커서로 쓰지 않는다.
-- 행 잠금이 같은 갤러리의 증가를 직렬화하므로 여러 서버·동시 트랜잭션에서도 커밋된 변경을 건너뛰지 않는다.
CREATE TABLE gallery_selection_sync (
    gallery_id BIGINT PRIMARY KEY REFERENCES galleries(id) ON DELETE CASCADE,
    selection_revision BIGINT NOT NULL DEFAULT 0,
    rating_revision BIGINT NOT NULL DEFAULT 0
);

CREATE FUNCTION bump_gallery_selection_sync(gid BIGINT, selection_delta BIGINT, rating_delta BIGINT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    -- 갤러리 자체를 지우는 FK cascade에서는 변경 번호를 다시 만들지 않는다.
    INSERT INTO gallery_selection_sync (gallery_id, selection_revision, rating_revision)
    SELECT id, selection_delta, rating_delta FROM galleries WHERE id = gid
    ON CONFLICT (gallery_id) DO UPDATE SET
        selection_revision = gallery_selection_sync.selection_revision + EXCLUDED.selection_revision,
        rating_revision = gallery_selection_sync.rating_revision + EXCLUDED.rating_revision;
END;
$$;

CREATE FUNCTION track_photo_selection_sync() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM bump_gallery_selection_sync(COALESCE(NEW.gallery_id, OLD.gallery_id), 1, 0);
    RETURN NULL;
END;
$$;

-- 서비스 외의 관리자·벌크 삭제도 같은 계약을 지킨다. 제출·철회는 앨범 상태 변경으로 감지한다.
CREATE TRIGGER photo_selection_items_sync
AFTER INSERT OR UPDATE OR DELETE ON photo_selection_items
FOR EACH ROW EXECUTE FUNCTION track_photo_selection_sync();

CREATE TRIGGER photo_selections_sync
AFTER INSERT OR UPDATE OR DELETE ON photo_selections
FOR EACH ROW EXECUTE FUNCTION track_photo_selection_sync();

CREATE FUNCTION track_photo_rating_sync() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE gid BIGINT;
BEGIN
    -- 휴지통 사진의 별점 삭제도 감지하므로 의도적으로 deleted_at 필터를 걸지 않는다.
    SELECT gallery_id INTO gid FROM photos WHERE id = COALESCE(NEW.photo_id, OLD.photo_id);
    IF gid IS NOT NULL THEN
        PERFORM bump_gallery_selection_sync(gid, 0, 1);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER photo_ratings_sync
AFTER INSERT OR UPDATE OR DELETE ON photo_ratings
FOR EACH ROW EXECUTE FUNCTION track_photo_rating_sync();

CREATE FUNCTION track_photo_visibility_sync() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.deleted_at IS NULL THEN
            PERFORM bump_gallery_selection_sync(OLD.gallery_id, 1, 1);
        END IF;
    ELSIF NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        -- 숨기기·복원은 별점과 선택 앨범 두 스냅샷의 노출 항목을 바꾼다.
        PERFORM bump_gallery_selection_sync(NEW.gallery_id, 1, 1);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER photos_selection_sync
AFTER UPDATE OF deleted_at OR DELETE ON photos
FOR EACH ROW EXECUTE FUNCTION track_photo_visibility_sync();
