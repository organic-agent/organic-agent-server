-- 업로드 파이프라인이 사진에 요구하는 것들.
--
-- 이 파일이 V1과 달리 모든 환경에서 실제로 실행된다. 운영 DB는 V1을 baseline으로 건너뛰고
-- 여기서부터 적용되므로, 확장 생성도 여기에 있어야 한다.

-- 벡터 컬럼보다 먼저 와야 한다. 이것이 스키마를 Hibernate가 아니라 마이그레이션이
-- 소유해야 하는 이유다 -- ddl-auto는 테이블을 만들기 전에 확장을 만들어 줄 수 없다.
-- RDS 마스터 계정(rds_superuser)으로 실행된다.
CREATE EXTENSION IF NOT EXISTS vector;

-- PENDING(presigned URL만 발급됨) -> UPLOADED(프론트가 완료 통보) -> EMBEDDED(벡터 적재됨)
--
-- 기존 행에는 DEFAULT로 값을 채운 뒤 DEFAULT를 떼어낸다. 지금 photos에 행이 있을 리는
-- 없지만(업로드 API가 이번에 처음 생긴다), 비어 있음을 전제로 쓴 마이그레이션은 전제가
-- 틀리는 순간 NOT NULL 위반으로 배포를 멈춰 세운다.
ALTER TABLE photos
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED';

ALTER TABLE photos
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE photos
    ADD COLUMN content_type VARCHAR(100) NOT NULL DEFAULT 'application/octet-stream';

ALTER TABLE photos
    ALTER COLUMN content_type DROP DEFAULT;

-- 768은 DINOv2-base의 CLS 토큰 차원이다. pgvector에서 차원은 타입의 일부라
-- vector(768)과 vector(512)는 서로 캐스팅되지 않는다 -- 모델을 바꿔 폭이 달라지면
-- 이 컬럼을 새 마이그레이션으로 갈아끼우고 Photo.EMBEDDING_DIMENSION과
-- 인프라의 embedding_dimension 변수도 함께 옮겨야 한다. 셋이 어긋나면 UPDATE가
-- DB에서 거절된다 -- 조용히 틀린 값이 들어가지는 않는다는 뜻이다.
ALTER TABLE photos
    ADD COLUMN embedding vector(768);

-- 임베딩 Lambda가 매번 여는 질의(`gallery_id = ? AND embedding IS NULL`)와
-- 상태 집계 조회가 같이 쓴다. 갤러리 하나가 수천 행이라 gallery_id 단독 인덱스로는
-- 대상 하나 남은 재실행에서도 전량을 훑는다.
CREATE INDEX idx_photos_gallery_id_status ON photos (gallery_id, status);
