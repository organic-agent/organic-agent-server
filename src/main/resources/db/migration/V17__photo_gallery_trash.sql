-- 휴지통: 사용자가 지운 사진·갤러리는 표시만 하고(deleted_at), 물리 삭제는 휴지통에서의
-- 재삭제 또는 보관 기간 만료 purge가 수행한다. 하위 행은 V9·V12의 FK cascade가 걷는다.
-- 엔티티의 @SQLRestriction("deleted_at is null")이 이 컬럼을 읽으므로 같은 배포로 나간다.
ALTER TABLE photos
    ADD COLUMN deleted_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE galleries
    ADD COLUMN deleted_at TIMESTAMP(6) WITH TIME ZONE;

-- 이 조건을 타는 것은 휴지통 목록과 purge 스캔뿐이라 부분 인덱스로 충분하다.
CREATE INDEX idx_photos_deleted_at
    ON photos (deleted_at)
    WHERE deleted_at IS NOT NULL;

CREATE INDEX idx_galleries_deleted_at
    ON galleries (deleted_at)
    WHERE deleted_at IS NOT NULL;

-- 운영자 hard delete 파이프라인 폐기(코드와 함께 제거). V9이 만든 점유·기록 테이블을 걷는다.
DROP TABLE studio_deletion_claims;
DROP TABLE studio_deletion_audits;
