-- 임베더 배정 시각 인덱스(#286 (l)). 5초 스윕의 세 문장(오래된 배정 되돌리기·떠 있는 배치 수·점수 대기 수)이 사진 테이블 전체를 훑었다.
--
-- 끝난 사진도 dispatched_at 을 그대로 갖고 있어서(dev 17,491장 전부), "보냈는데 아직 안 끝난 사진"을 찾으려면 끝난 사진까지 하나하나
-- photo_analysis 와 맞춰 봐야 했다 — 한가한 dev 에서 문장당 21~24ms, 부하 중(R-3-c) 300~500ms, 사진이 쌓일수록 계속 커진다.
-- 이 인덱스로 세 문장이 "최근 배정"만 시각 범위로 읽는다(범위는 `PhotoPipelineRepository`의 각 문장이 말한다).
CREATE INDEX idx_photos_dispatched ON photos (dispatched_at)
    WHERE status = 'UPLOADED' AND deleted_at IS NULL AND dispatched_at IS NOT NULL;
