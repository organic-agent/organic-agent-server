-- 추천 노출 기록(#391 PR-2, ADR 0001). 재추천이 범위 사진의 옛 추천 행을 지우지 않고 갈린 시각만 남긴다 —
-- "보여줬는데 안 담은" 사진을 다음 추천이 약하게 내리는 근거다. 화면은 replaced_at IS NULL 인 행만 그린다.
-- NULL 허용 컬럼 추가라 옛 서버 코드(롤링 배포 중)가 그대로 돈다. 백필 없음 — 옛 라운드 행은 이미 지워져 있다.
-- 되돌릴 때: 코드를 옛 DELETE 로 돌리면 옛 화면 조회가 갈린 행을 다시 그리므로
-- DELETE FROM ai_recommendations WHERE replaced_at IS NOT NULL AND rejected_at IS NULL 을 함께 돌린다.
ALTER TABLE ai_recommendations ADD COLUMN replaced_at timestamp(6) with time zone;
