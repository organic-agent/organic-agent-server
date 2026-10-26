-- 사진 라벨 색인(#348 ADR-1). 사진마다 닫힌 어휘 라벨(눈 감음·웃음·베일·부케·소품·클로즈업·상반신·전신·뒷모습)과
-- "얼마나 그럴듯한가" 점수를 둔다. 셀렉 도우미가 "눈 감은 사진 빼줘" 같은 조건을 거르는 재료다.
--
--   누가 쓰나 : score 워커(DB 유저 photoselect)가 1단계(CLIP·피사체 제로샷) 옆에서 9개 점수를 전부 쓴다 — 임계값 미달도 남긴다.
--               그래야 임계값을 바꿀 때 재계산 없이 조건만 옮긴다. 2층(자체 VLM)은 돌린 라벨만 쓴다. 한 (사진, 라벨)엔 한 source.
--   누가 읽나 : wes 만 읽는다(엔티티 없음, native 조회). "라벨이 붙었다" = score >= 임계값(label). 라벨 코드와 임계값은
--               두 레포(wes AgentLabel · AI score/labels.py)의 코드 상수이고 DB 는 모른다 — 어휘를 늘릴 때 마이그레이션이 없다.
--   언제 지우나: 사진 삭제를 따라간다(ON DELETE CASCADE). relabel(어휘·프롬프트 변경 뒤 저장된 clip_embedding 으로 다시 계산)은
--               그 갤러리·source 행을 DELETE 하고 다시 쓴다.
--   label      : AgentLabel 코드(예: EYES_CLOSED). source : CLIP · PROBE · VLM · FACE.
CREATE TABLE photo_labels (
    photo_id   bigint       NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    label      varchar(30)  NOT NULL,
    score      real         NOT NULL,
    source     varchar(10)  NOT NULL,
    labeled_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (photo_id, label),
    CONSTRAINT ck_photo_labels_score CHECK (score >= 0 AND score <= 1)
);

-- 라벨 한 가지로 사진을 찾는 조회("전신 사진 전부")와 갤러리 사진 집합과의 조인이 여기서 출발한다. PK 는 사진에서 라벨로만 간다.
CREATE INDEX idx_photo_labels_label ON photo_labels (label, photo_id);

-- photoselect 계약 — V23 블록을 통째로 복사하고 photo_labels 를 더한다(V16 → V22 → V23 과 같은 방식, 적용된 파일은 고치지 않는다).
-- DELETE 는 relabel 이 그 갤러리·source 행을 지우고 다시 쓰기 위한 것이다. AdminEmbedderPrivilegeContractTest 가 이 블록을 꺼내 실행한다.
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
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON public.photo_labels TO photoselect';
        EXECUTE 'GRANT UPDATE (error, updated_at) ON public.analysis_jobs TO photoselect';
        EXECUTE 'GRANT UPDATE ON public.ai_selection_jobs TO photoselect';
    END IF;
END
$$;
-- PHOTOSELECT_GRANT_CONTRACT_END
