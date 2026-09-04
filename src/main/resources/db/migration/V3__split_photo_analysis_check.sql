-- AI 폴더화 배치가 SCORE·CATEGORIZE 두 잡으로 갈라졌다(AI repo #30). SCORE는 subjects·sub_scores·clip_embedding·
-- model_version만 쓰고, 백분위·연사·임베딩 그룹은 CATEGORIZE가 뒤에 한 UPDATE로 채운다. 기존 제약은
-- "model_version이 있으면 전부 채워져 있다"를 강제해 SCORE의 첫 쓰기가 실패한다. 잡 단위로 나눠 건다:
--   scored      : model_version이 있으면 subjects·analyzed_at도 있다 (SCORE가 한 번에 쓰는 것)
--   categorized : 백분위·연사·그룹은 전부 있거나 전부 없다 (CATEGORIZE가 한 번에 쓰는 것)
-- "분석 완료"는 이제 DB 제약이 아니라 PhotoAnalysis.isAnalyzed(model_version + 백분위)가 판단한다.
ALTER TABLE photo_analysis DROP CONSTRAINT ck_photo_analysis_analyzed;

ALTER TABLE photo_analysis ADD CONSTRAINT ck_photo_analysis_scored CHECK (
    model_version IS NULL OR (subjects IS NOT NULL AND analyzed_at IS NOT NULL)
);

ALTER TABLE photo_analysis ADD CONSTRAINT ck_photo_analysis_categorized CHECK (
    (technical_pct IS NULL AND aesthetic_pct IS NULL
        AND cluster_id IS NULL AND cluster_rank IS NULL AND embed_group_id IS NULL)
    OR (technical_pct IS NOT NULL AND aesthetic_pct IS NOT NULL
        AND cluster_id IS NOT NULL AND cluster_rank IS NOT NULL AND embed_group_id IS NOT NULL)
);
