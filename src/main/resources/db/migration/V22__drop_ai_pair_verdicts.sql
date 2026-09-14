-- 비교샷 제거 — 두 사진 중 하나를 AI 가 고르고 근거를 말하던 기능을 쓰지 않기로 했다.
--
-- POST /api/v1/galleries/{galleryId}/photo-selection/compare 와 판정 캐시 테이블을 함께 지운다. 아직 운영 서비스가
-- 아니라 변환 없이 삭제한다(V17 categorization_* 과 같은 방식). 추천 이유 문장이 형제(연사) 컷을 설명할 때 쓰던
-- 선명도 비·백분위 차 기준은 ReasonMaterial 로 옮겼으므로 추천은 그대로 돈다.

-- 1. 판정 캐시 제거. 이 테이블을 참조하는 FK 는 없다(photo_selections·photos 를 가리키기만 했다).
DROP TABLE public.ai_pair_verdicts;

-- 2. photoselect role 계약 — V16 블록을 대체하는 전체 계약. ai_pair_verdicts 만 빠지고 나머지는 V16 과 같다
--    (V16 은 이미 적용된 파일이라 고치지 않는다 — 계약 테스트는 이 블록만 실행한다).
-- PHOTOSELECT_GRANT_CONTRACT_BEGIN
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'photoselect') THEN
        EXECUTE 'REVOKE ALL ON public.ai_analysis_jobs FROM photoselect';
        EXECUTE 'GRANT SELECT ON public.galleries, public.photos, public.photo_analysis, '
                'public.concept_folders, public.detail_folders, public.photo_category_assignments, '
                'public.photo_selections, public.photo_selection_items, '
                'public.ai_analysis_jobs, public.ai_concept_assignments, public.ai_selection_jobs, '
                'public.ai_recommendations '
                'TO photoselect';
        EXECUTE 'GRANT INSERT, UPDATE ON public.photo_analysis, public.ai_concept_assignments, '
                'public.ai_recommendations '
                'TO photoselect';
        EXECUTE 'GRANT UPDATE (error, updated_at) ON public.ai_analysis_jobs TO photoselect';
        EXECUTE 'GRANT UPDATE ON public.ai_selection_jobs TO photoselect';
    END IF;
END
$$;
-- PHOTOSELECT_GRANT_CONTRACT_END
