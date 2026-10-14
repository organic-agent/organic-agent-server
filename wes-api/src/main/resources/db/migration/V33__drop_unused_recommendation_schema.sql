-- 추천 이유 문장·자연어 요청 해석 제거(#234)의 남은 컬럼. 엔티티는 #234 부터 읽지 않는다.
-- #234 와 같은 배포에서 지우지 않은 이유: 배포가 롤백되면 이전 이미지가 ddl-auto=validate 에서 이 컬럼을 찾는다.
ALTER TABLE ai_recommendations DROP COLUMN IF EXISTS reason;
ALTER TABLE ai_selection_jobs
    DROP COLUMN IF EXISTS request_prompt,
    DROP COLUMN IF EXISTS resolved_query;

-- 여러 갤러리를 모아 학습하는 공용 선호 가중치(V14). 쓰는 Lambda(wes-preference)는 배포된 적이 없고 서버는 읽지 않는다.
-- 부분 유니크 인덱스(ux_preference_models_active)와 photoselect GRANT 는 테이블과 함께 사라진다.
DROP TABLE IF EXISTS preference_models;
