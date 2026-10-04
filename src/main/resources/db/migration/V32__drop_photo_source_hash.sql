-- 지문(source_hash) 기반 중복 업로드 방지를 걷어낸다(V28). web 은 지문을 보내지 않고 파일 이름으로 중복을 거른다.
-- V28 의 uploaded_at 은 분석 단계가 쓰므로 남긴다.
DROP INDEX IF EXISTS idx_photos_trashed_source_hash;
DROP INDEX IF EXISTS uk_photos_gallery_source_hash;
ALTER TABLE photos DROP COLUMN IF EXISTS source_hash;
