-- folder 도메인 제거 (#129).
-- 사진 묶음은 category 도메인(concept_folders → detail_folders → photo_category_assignments)만 남는다.
-- 옛 photo_folder_* 와 그 위에 얹혀 있던 관리자 앨범 템플릿(admin_album_templates, ALBUM 리소스)을 함께 지운다.

-- 1. ALBUM / ALBUM_TEMPLATE 를 가리키던 관리자 운영 행 정리. 감사 로그(admin_audit_logs)는 남긴다.
DELETE FROM public.admin_child_trash_records WHERE resource_type = 'ALBUM_TEMPLATE' OR parent_type = 'ALBUM';
DELETE FROM public.admin_trash_entries WHERE resource_type = 'ALBUM';
DELETE FROM public.admin_trash_batches WHERE root_type = 'ALBUM';
DELETE FROM public.admin_notification_inbox WHERE target_type = 'ALBUM';
DELETE FROM public.admin_idempotency_keys WHERE target_type = 'ALBUM';
DELETE FROM public.admin_processing_jobs WHERE target_type = 'ALBUM' OR job_type = 'MOCK_RECALCULATION';
DELETE FROM public.admin_entity_revisions WHERE target_type = 'ALBUM';

-- 2. 리소스 종류를 열거하는 CHECK 에서 ALBUM 을 뺀다.
ALTER TABLE public.admin_child_trash_records DROP CONSTRAINT ck_admin_child_trash_resource_parent;
ALTER TABLE public.admin_child_trash_records ADD CONSTRAINT ck_admin_child_trash_resource_parent CHECK (
    (resource_type = 'COLLAB_COMMENT' AND parent_type = 'COLLABORATION')
    OR (resource_type = 'COLLAB_LIKE' AND parent_type = 'COLLABORATION')
    OR (resource_type = 'RETOUCH_ITEM' AND parent_type = 'RETOUCH_REQUEST')
);

ALTER TABLE public.admin_notification_inbox DROP CONSTRAINT ck_admin_notification_inbox_target_type;
ALTER TABLE public.admin_notification_inbox ADD CONSTRAINT ck_admin_notification_inbox_target_type CHECK (
    target_type IN (
        'USER', 'WORKSPACE', 'STUDIO', 'GALLERY', 'PHOTO',
        'CONCEPT_FOLDER', 'DETAIL_FOLDER', 'PHOTO_CATEGORY_ASSIGNMENT', 'CATEGORIZATION_JOB',
        'PHOTO_RATING', 'SELECTION', 'COLLABORATION', 'RETOUCH_REQUEST'
    )
);

-- 앨범 목업 재계산 잡도 앨범과 함께 사라진다.
ALTER TABLE public.admin_processing_jobs DROP CONSTRAINT ck_admin_processing_job_type;
ALTER TABLE public.admin_processing_jobs ADD CONSTRAINT ck_admin_processing_job_type CHECK (
    job_type IN ('DERIVATIVE', 'EMBEDDING', 'QUALITY_ANALYSIS')
);

-- 3. 테이블 제거. 자식 → 부모 순 (photo_folder_groups 는 admin_album_templates 를 참조한다).
DROP TABLE public.photo_folder_items;
DROP TABLE public.photo_folders;
DROP TABLE public.photo_folder_groups;
DROP TABLE public.admin_album_templates;
