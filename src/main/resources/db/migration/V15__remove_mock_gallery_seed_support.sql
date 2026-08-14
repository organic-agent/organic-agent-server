-- Mock 갤러리 재설계: 공유 템플릿 참조 모델(V11)을 폐기하고 V11 이전 계약으로 되돌린다.
-- 새 설계는 템플릿 갤러리의 사진과 S3 객체를 갤러리별로 복제하므로, 사진 한 장이 곧
-- 자기 S3 객체의 유일한 참조라는 원래 불변식이 다시 성립한다.
-- V11 파일은 prod에 적용된 이력이 있어 삭제·수정할 수 없다(Flyway checksum validate).

-- 1) MOCK 갤러리 행 삭제. photos는 fk_photos_gallery ON DELETE CASCADE(V9)로, 별점·폴더·
--    선택 앨범·협업 행들은 각자의 FK cascade로 함께 지워진다. 반드시 3)보다 먼저다 —
--    MOCK 행들은 같은 mock-gallery/v1 key를 공유하므로, 남아 있으면 전역 유니크 복원이
--    unique violation으로 실패한다. S3의 mock-gallery/ 객체는 여기서 지우지 않는다(운영 절차).
DELETE FROM galleries WHERE gallery_type = 'MOCK';

-- 2) galleries: mock 식별자 제거. 인덱스·제약이 컬럼을 참조하므로 컬럼보다 먼저 걷는다.
DROP INDEX uk_galleries_studio_mock;
ALTER TABLE galleries
    DROP CONSTRAINT ck_galleries_mock_template_version,
    DROP CONSTRAINT ck_galleries_type;
ALTER TABLE galleries
    DROP COLUMN template_version,
    DROP COLUMN gallery_type;

-- 3) photos: 소유권 경계 제거와 storage_key 전역 유니크 복원.
--    GALLERY 사진끼리는 부분 인덱스 uk_photos_gallery_owned_storage_key가 유일성을 이미
--    보장해 왔으므로, 1)이 선행된 뒤의 ADD CONSTRAINT는 실패할 수 없다.
DROP INDEX uk_photos_gallery_owned_storage_key;
ALTER TABLE photos
    DROP CONSTRAINT uk_photos_gallery_id_storage_key,
    DROP CONSTRAINT ck_photos_shared_template_no_upload_url,
    DROP CONSTRAINT ck_photos_shared_template_ready,
    DROP CONSTRAINT ck_photos_storage_namespace,
    DROP CONSTRAINT ck_photos_storage_ownership;
ALTER TABLE photos
    ADD CONSTRAINT uk_photos_storage_key UNIQUE (storage_key);
ALTER TABLE photos
    DROP COLUMN storage_ownership;
