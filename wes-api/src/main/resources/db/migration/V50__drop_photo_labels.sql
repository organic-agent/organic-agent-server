-- 사진 라벨 표 photo_labels(V46, #348)를 지운다. 셀렉 도우미를 접어(2026-11-04) 쓰는 쪽이 없다 —
-- wes 는 읽은 적이 없고(엔티티·조회 없음), 쓰려던 score 워커 PR(AI #157)은 머지하지 않고 닫았다. 그래서 행도 없다.
-- 표를 지우면 그 표의 GRANT 도 함께 사라진다.
DROP TABLE IF EXISTS photo_labels;

-- photoselect 계약 — V46 블록에서 photo_labels 줄만 뺀 블록으로 대체한다(= V23 블록과 같음, 적용된 파일은 고치지 않는다).
-- AdminEmbedderPrivilegeContractTest 가 이 블록을 꺼내 실행한다.
-- PHOTOSELECT_GRANT_CONTRACT_BEGIN
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'photoselect') THEN
        EXECUTE 'REVOKE ALL ON public.analysis_jobs FROM photoselect';
        EXECUTE 'GRANT SELECT ON public.galleries, public.photos, public.photo_analysis, '
                'public.concept_folders, public.detail_folders, public.detail_folder_assignments, '
                'public.photo_selections, public.photo_selection_items, '
                'public.analysis_jobs, public.concept_assignments, public.ai_selection_jobs, '
                'public.ai_recommendations '
                'TO photoselect';
        EXECUTE 'GRANT INSERT, UPDATE ON public.photo_analysis, public.concept_assignments, '
                'public.ai_recommendations '
                'TO photoselect';
        EXECUTE 'GRANT UPDATE (error, updated_at) ON public.analysis_jobs TO photoselect';
        EXECUTE 'GRANT UPDATE ON public.ai_selection_jobs TO photoselect';
    END IF;
END
$$;
-- PHOTOSELECT_GRANT_CONTRACT_END
