-- 용어집 2단계 — AI(organic-agent-ai)와 서버가 같은 이름을 쓰도록 DB 이름을 맞춘다.
--
-- 이름의 정본은 WES-DOCS docs/glossary.md 다. 코드(엔티티 필드, AI dataclass 필드)는 1단계에서 이미 새 이름이고, 이 마이그레이션이
-- 컬럼·테이블을 그 이름에 맞춘다. AI Lambda·GPU 워커가 이 테이블을 직접 읽고 쓰므로 **분석을 멈추고 AI 배포와 같은 창에서** 적용한다
-- (AI repo 브랜치 refactor/glossary-names 의 2단계 커밋). 권한(GRANT)과 CHECK·FK 는 테이블·컬럼 번호에 붙어 있어 이름을 바꿔도 따라간다.

-- 컨셉 배정: `concept_name` 이 2층을 가리키던 것을 바로잡는다. 2층 컬럼을 먼저 비워야 1층이 그 이름을 쓸 수 있다.
ALTER TABLE ai_concept_assignments RENAME COLUMN concept_name TO detail_name;
ALTER TABLE ai_concept_assignments RENAME COLUMN parent_name TO concept_name;
ALTER TABLE ai_concept_assignments RENAME COLUMN proposed_parent TO proposed_concept_name;
ALTER TABLE ai_concept_assignments RENAME COLUMN clip_parent TO clip_concept_name;
ALTER TABLE ai_concept_assignments RENAME TO concept_assignments;

-- 분석 잡: 출처 표시(ai_)는 개념이 아니다(용어집 D4).
ALTER TABLE ai_analysis_jobs RENAME TO analysis_jobs;

-- 사진 분석: 연사(burst)와 파이프라인 버전. model_version 은 모델 id 가 아니라 score·categorize 계산 방식의 버전이다(D3).
ALTER TABLE photo_analysis RENAME COLUMN cluster_id TO burst_id;
ALTER TABLE photo_analysis RENAME COLUMN cluster_rank TO burst_rank;
ALTER TABLE photo_analysis RENAME COLUMN model_version TO pipeline_version;

-- 선호 모델이 기록하는 값도 학습 데이터의 파이프라인 버전이다.
ALTER TABLE preference_models RENAME COLUMN model_version TO pipeline_version;

-- 사진은 세부 폴더에만 배정된다(D9). 컷 종류는 category 가 아니다.
ALTER TABLE photo_category_assignments RENAME TO detail_folder_assignments;
ALTER TABLE detail_folders RENAME COLUMN category TO cut_type;

-- 폴더 세트의 키는 분석 잡 id 다.
ALTER TABLE ai_selection_jobs RENAME COLUMN folder_set_job_id TO analysis_job_id;

-- 제약·인덱스·시퀀스 이름에 남은 옛 테이블·컬럼 이름. 이미 지운 것이 섞여 있어 목록을 손으로 적지 않고 카탈로그에서 찾는다.
-- 제약을 먼저 바꾼다 — PK·UNIQUE 제약의 이름을 바꾸면 그 뒤의 인덱스 이름도 함께 바뀐다.
DO $$
DECLARE
    pair text[];
    r record;
    pairs text[][] := ARRAY[
        ARRAY['ai_concept_assignments', 'concept_assignments'],
        ARRAY['ai_analysis_jobs', 'analysis_jobs'],
        ARRAY['photo_category_assignments', 'detail_folder_assignments'],
        ARRAY['detail_folders_category', 'detail_folders_cut_type']
    ];
BEGIN
    FOREACH pair SLICE 1 IN ARRAY pairs LOOP
        FOR r IN
            SELECT c.conname, c.conrelid::regclass AS tbl
            FROM pg_constraint c
            WHERE c.connamespace = 'public'::regnamespace AND c.conname LIKE '%' || pair[1] || '%'
        LOOP
            EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I', r.tbl, r.conname, replace(r.conname, pair[1], pair[2]));
        END LOOP;
        FOR r IN
            SELECT cl.relname, cl.relkind
            FROM pg_class cl
            WHERE cl.relnamespace = 'public'::regnamespace AND cl.relkind IN ('i', 'S')
              AND cl.relname LIKE '%' || pair[1] || '%'
        LOOP
            IF r.relkind = 'i' THEN
                EXECUTE format('ALTER INDEX public.%I RENAME TO %I', r.relname, replace(r.relname, pair[1], pair[2]));
            ELSE
                EXECUTE format('ALTER SEQUENCE public.%I RENAME TO %I', r.relname, replace(r.relname, pair[1], pair[2]));
            END IF;
        END LOOP;
    END LOOP;
END
$$;

-- jsonb 키. score 가 쓰는 세부 점수의 CLIP 컨셉 라벨, 추천 근거의 연사 번호.
UPDATE photo_analysis
SET sub_scores = (sub_scores - 'clip_parent') || jsonb_build_object('clip_concept_name', sub_scores -> 'clip_parent')
WHERE sub_scores ? 'clip_parent';

UPDATE ai_recommendations
SET score_breakdown = (score_breakdown - 'cluster_id') || jsonb_build_object('burst_id', score_breakdown -> 'cluster_id')
WHERE score_breakdown ? 'cluster_id';

-- photoselect 계약은 새 테이블 이름으로 이 블록이 V22 블록을 통째로 대체한다(V22 블록은 없는 이름을 가리킨다).
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
