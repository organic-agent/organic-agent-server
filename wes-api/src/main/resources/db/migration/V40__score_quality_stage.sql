-- 점수를 두 단계로 나눈다(#274 2물결, ADR 0002 B). 업로드 경로는 폴더에 필요한 CLIP·피사체(1단계)까지만 기다리고,
-- 추천에만 쓰는 화질 점수(ARNIQA·classical, 2단계)는 폴더가 만들어진 뒤 GPU 워커가 채운다. 백분위·연사 대표 순위는
-- 갤러리 전부에 2단계가 차면 categorize rank 모드가 채운다.
--
--   quality_scored_at : 2단계(화질 점수)가 끝난 시각. score 가 쓴다. 계산에 실패해도 찍는다(점수만 null) — error 에 쓰면
--                       그 사진이 폴더 대상에서 빠진다.
--   rank_dispatched_at·rank_attempts : 이 서버가 categorize rank 모드를 보낸 시각·횟수(재전송 판단).

ALTER TABLE photo_analysis ADD COLUMN quality_scored_at TIMESTAMP(6) WITH TIME ZONE;

-- 지금까지 점수가 난 행은 2단계까지 한 번에 계산됐다. 백필하지 않으면 배포하자마자 옛 갤러리 전부가 2단계 대기가 되어 GPU가 켜진다.
UPDATE photo_analysis SET quality_scored_at = analyzed_at WHERE clip_embedding IS NOT NULL;

-- 2단계 대기 — GPU 워커의 2단계 집기와 이 서버의 대기 수 세기가 여기서 출발한다(1단계의 idx_photo_analysis_unscored 와 짝).
CREATE INDEX idx_photo_analysis_quality_unscored ON photo_analysis (photo_id)
    WHERE clip_embedding IS NOT NULL AND quality_scored_at IS NULL AND error IS NULL;

-- 순위 대기 — 그룹(폴더)은 있는데 백분위(추천)가 빈 사진. rank 모드를 보낼 갤러리 찾기와 추천의 "준비 중" 판정이 여기서 출발한다.
CREATE INDEX idx_photo_analysis_unranked ON photo_analysis (photo_id)
    WHERE embed_group_id IS NOT NULL AND technical_pct IS NULL AND error IS NULL;

-- 분류 제약을 둘로 나눈다. 그룹·연사 묶음(폴더용)은 점수 없이 먼저 쓰이고, 백분위·연사 대표 순위(추천용)는 뒤에 쓰인다.
--   grouped : 그룹과 연사 묶음은 함께 있거나 함께 없다 (categorize full 이 한 번에 쓴다)
--   ranked  : 백분위 둘과 순위는 함께 있거나 함께 없고, 있으면 그룹도 있다 (full 또는 rank 모드가 한 번에 쓴다)
ALTER TABLE photo_analysis DROP CONSTRAINT ck_photo_analysis_categorized;

ALTER TABLE photo_analysis ADD CONSTRAINT ck_photo_analysis_grouped CHECK (
    (embed_group_id IS NULL) = (burst_id IS NULL)
);

ALTER TABLE photo_analysis ADD CONSTRAINT ck_photo_analysis_ranked CHECK (
    (technical_pct IS NULL AND aesthetic_pct IS NULL AND burst_rank IS NULL)
    OR (technical_pct IS NOT NULL AND aesthetic_pct IS NOT NULL AND burst_rank IS NOT NULL AND embed_group_id IS NOT NULL)
);

ALTER TABLE analysis_jobs ADD COLUMN rank_dispatched_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE analysis_jobs ADD COLUMN rank_attempts INTEGER NOT NULL DEFAULT 0;
