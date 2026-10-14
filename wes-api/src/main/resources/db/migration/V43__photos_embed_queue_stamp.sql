-- 임베더 단계를 떠난 사진에 배정 시각을 남긴다(#286 B-1). 임베딩 대기열 인덱스(idx_photos_embed_queue)는 photos 만 보고 "배정 시각이 비었으면
-- 할 일"로 치는데, 끝났는지는 photo_analysis 에 있다 — 인덱스가 두 테이블을 함께 볼 수 없어, 배정 시각이 빈 채 끝난 사진은 대기열에 남아
-- 5초 스윕이 매번 분석 행과 맞춰 본다. 쌓일수록 무거워진다(dev B-1 복제 사진 99,800장: 대기 갤러리 찾기 최대 31초 · 디스크 5.8GB).
--
-- 빈 채 끝난 사진은 배정 시각 칸(V15) 전에 올라온 사진(운영 2026-10-14 조회: 80장, 09-05 하루치)과 시도 상한으로 실패를 확정한 사진이다.
-- 뒤엣것은 이제 확정 때 함께 찍는다(PhotoPipelineRepository.markEmbedAttemptsExceeded · markAnalysisStalled). 값은 올라온 시각 — 배정 시각을
-- 보는 문장(되돌리기·떠 있는 배치 세기)은 벡터·실패가 있는 사진을 대상으로 삼지 않으므로 어떤 값이든 동작을 바꾸지 않는다.
UPDATE photos p
SET dispatched_at = coalesce(p.uploaded_at, p.created_at)
WHERE p.status = 'UPLOADED' AND p.dispatched_at IS NULL
  AND EXISTS (SELECT 1 FROM photo_analysis a WHERE a.photo_id = p.id AND (a.embedding IS NOT NULL OR a.error IS NOT NULL));
