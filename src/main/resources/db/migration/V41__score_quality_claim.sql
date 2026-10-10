-- 화질 점수(score 2단계)의 집기를 "찜 표시"로 바꾼다(#274 R-2-2, 방식 B). GPU 워커가 계산하는 동안 행 잠금을 쥐지 않게 한다.
--
-- R-2-1: 워커는 2단계 사진 32장을 `FOR UPDATE` 로 잠근 채 내려받고 계산했다(배치당 1~2초, 레인 둘). 같은 시각 categorize 가
-- 같은 행에 폴더 묶음을 쓰다 그 잠금을 기다려 7,189행 적재가 16초 → 95초, DB 시간의 55%였다.
-- 이제 워커는 이 컬럼에 집은 시각을 찍고 곧바로 commit 한다. 계산 뒤에는 잠글 수 있는 행에만 한 문장으로 쓴다(score 쪽 SKIP LOCKED).
--
--   quality_claimed_at : 화질 점수를 계산하려고 워커가 집은 시각. score 가 쓰고 결과를 쓸 때 지운다. 이 시각이 오래되면(워커가 죽음)
--                        다른 워커가 다시 집는다. 이 서버는 읽지 않는다 — 대기 수는 `quality_scored_at IS NULL` 그대로다.
ALTER TABLE photo_analysis ADD COLUMN quality_claimed_at TIMESTAMP(6) WITH TIME ZONE;
