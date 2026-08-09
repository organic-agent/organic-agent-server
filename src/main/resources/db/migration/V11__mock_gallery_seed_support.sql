-- WES-21 Mock 갤러리와 공유 샘플 사진 seed를 위한 식별자·소유권 경계.
--
-- V9·V10은 WES-19의 cascade hard delete가 사용한다. 이 브랜치는 아직 그 변경이 없는
-- origin/main에서 시작했지만, 사이클 순서대로 WES-19 다음에 합쳐져도 번호가 충돌하지 않게
-- V11부터 사용한다. Flyway는 중간 버전이 아직 없는 개발 DB에서도 순서대로 적용할 수 있다.

ALTER TABLE galleries
    ADD COLUMN gallery_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE galleries
    ALTER COLUMN gallery_type DROP DEFAULT;

ALTER TABLE galleries
    ADD COLUMN template_version VARCHAR(50);

ALTER TABLE galleries
    ADD CONSTRAINT ck_galleries_type
        CHECK (gallery_type IN ('NORMAL', 'MOCK')),
    ADD CONSTRAINT ck_galleries_mock_template_version
        CHECK (
            (gallery_type = 'NORMAL' AND template_version IS NULL)
            OR (gallery_type = 'MOCK' AND template_version IS NOT NULL)
        );

-- 없는 Mock 행은 잠글 수 없으므로 서비스가 스튜디오 행을 잠그고 생성한다. 이 인덱스는
-- 다른 쓰기 경로가 생겨도 최종적으로 스튜디오당 하나만 남기는 DB 방어선이다.
CREATE UNIQUE INDEX uk_galleries_studio_mock
    ON galleries (studio_id)
    WHERE gallery_type = 'MOCK';

ALTER TABLE photos
    ADD COLUMN storage_ownership VARCHAR(30) NOT NULL DEFAULT 'GALLERY';

ALTER TABLE photos
    ALTER COLUMN storage_ownership DROP DEFAULT;

ALTER TABLE photos
    ADD CONSTRAINT ck_photos_storage_ownership
        CHECK (storage_ownership IN ('GALLERY', 'SHARED_TEMPLATE')),
    ADD CONSTRAINT ck_photos_storage_namespace
        CHECK (
            storage_ownership <> 'GALLERY'
            OR (
                storage_key NOT LIKE 'mock-gallery/%'
                AND (preview_key IS NULL OR preview_key NOT LIKE 'mock-gallery/%')
            )
        ),
    ADD CONSTRAINT ck_photos_shared_template_ready
        CHECK (
            storage_ownership <> 'SHARED_TEMPLATE'
            OR (
                storage_key LIKE 'mock-gallery/%'
                AND preview_key LIKE 'mock-gallery/%'
                AND embedding IS NOT NULL
                AND status = 'EMBEDDED'
            )
        );

-- 일반 업로드 key는 애초에 gallery id를 포함하므로 갤러리 안에서만 유일하면 충분하다.
-- 공유 템플릿은 여러 갤러리가 같은 불변 S3 객체를 참조해야 해 전역 유니크를 유지할 수 없다.
ALTER TABLE photos
    DROP CONSTRAINT uk_photos_storage_key;

ALTER TABLE photos
    ADD CONSTRAINT uk_photos_gallery_id_storage_key UNIQUE (gallery_id, storage_key);

-- 일반 사진은 삭제 시 해당 S3 객체도 함께 지우므로 서로 다른 갤러리가 같은 key를 참조하면
-- 안 된다. 공유 템플릿만 부분 인덱스 밖에 두어 여러 갤러리에서 같은 불변 객체를 참조한다.
CREATE UNIQUE INDEX uk_photos_gallery_owned_storage_key
    ON photos (storage_key)
    WHERE storage_ownership = 'GALLERY';
