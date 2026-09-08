-- 분석 파이프라인 v2 — 정리(#170, docs/plans/pipeline-v2-wes.md Phase 4).
--
-- 폴더 물질화 기록(categorization_jobs·categorization_job_photos)을 지운다. v2 에서는 잡(ai_analysis_jobs)이 DONE 직전에 폴더를
-- 자동으로 만들고, "처리한 사진" 집합은 photo_category_assignments 가 이미 말한다. 프론트는 /categorization-jobs API 를 쓰지 않았고,
-- 7천 장 갤러리에서 job_photos INSERT 가 물질화 시간을 먹었다(#160). 아직 운영 서비스가 아니라 변환 대신 삭제한다.

-- 1. CATEGORIZATION_JOB 을 가리키던 관리자 운영 행 정리(V2 의 ALBUM 과 같은 방식). 감사 로그(admin_audit_logs)는 남긴다 —
--    AdminAuditTargetType 의 값도 옛 로그를 읽기 위해 남긴다.
DELETE FROM public.admin_notification_inbox WHERE target_type = 'CATEGORIZATION_JOB';
DELETE FROM public.admin_idempotency_keys WHERE target_type = 'CATEGORIZATION_JOB';
DELETE FROM public.admin_entity_revisions WHERE target_type = 'CATEGORIZATION_JOB';

-- 2. 리소스 종류를 열거하는 CHECK 에서 CATEGORIZATION_JOB 을 뺀다.
ALTER TABLE public.admin_notification_inbox DROP CONSTRAINT ck_admin_notification_inbox_target_type;
ALTER TABLE public.admin_notification_inbox ADD CONSTRAINT ck_admin_notification_inbox_target_type CHECK (
    target_type IN (
        'USER', 'WORKSPACE', 'STUDIO', 'GALLERY', 'PHOTO',
        'CONCEPT_FOLDER', 'DETAIL_FOLDER', 'PHOTO_CATEGORY_ASSIGNMENT',
        'PHOTO_RATING', 'SELECTION', 'COLLABORATION', 'RETOUCH_REQUEST'
    )
);

-- 3. 테이블 제거. 자식 → 부모 순(V6 의 복합 FK 도 함께 사라진다).
DROP TABLE public.categorization_job_photos;
DROP TABLE public.categorization_jobs;
