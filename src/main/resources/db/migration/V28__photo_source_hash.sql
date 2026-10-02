-- 멱등 업로드. 같은 원본을 다시 올려도 사진 행이 하나만 남게 한다.
--
-- source_hash: web 이 원본에서 계산한 지문(`{바이트 크기}-{앞 64KB 의 CRC32C hex}`). 서버는 문자열로만 받는다.
--              NULL 은 지문 없이 올라온 사진(옛 web, Mock 갤러리 복제)이고 중복 검사에서 빠진다.
-- uploaded_at: UPLOADED 가 된 시각. 완료 통보와 서버의 HeadObject 보정이 채운다.
ALTER TABLE photos ADD COLUMN source_hash varchar(40);
ALTER TABLE photos ADD COLUMN uploaded_at timestamp(6) with time zone;

-- 이미 올라온 사진은 정확한 시각을 모른다. 마지막 변경 시각이 가장 가까운 값이다.
UPDATE photos SET uploaded_at = updated_at WHERE status = 'UPLOADED';

-- 한 갤러리에서 살아 있는 사진의 지문은 하나뿐이다. 휴지통 사진은 빼서, 지운 사진과 같은 원본을 다시 올릴 수 있게 한다.
CREATE UNIQUE INDEX uk_photos_gallery_source_hash
    ON photos (gallery_id, source_hash)
    WHERE source_hash IS NOT NULL AND deleted_at IS NULL;

-- 지문 조회는 휴지통 사진도 본다(다시 올릴지 물어보기 위해). 위 유니크 인덱스는 살아 있는 행만 담으므로 따로 둔다.
CREATE INDEX idx_photos_trashed_source_hash
    ON photos (gallery_id, source_hash)
    WHERE source_hash IS NOT NULL AND deleted_at IS NOT NULL;
