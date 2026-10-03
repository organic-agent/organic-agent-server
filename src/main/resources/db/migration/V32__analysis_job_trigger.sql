-- 분석 잡을 서버가 만든다.
--
-- trigger:     누가 만들었나. USER(분석 요청 API) · AUTO(업로드가 조용해져 서버가 만듦) · RETRY(일시적 실패 뒤 자동 재시도)
--              · ADMIN(관리자 재처리).
-- retry_count: 이 잡이 몇 번째 자동 재시도인가. USER·AUTO·ADMIN 은 0, RETRY 는 앞 잡의 값 + 1.
--              일시적 실패가 이어져도 정해진 횟수에서 멈추게 하는 값이다.
ALTER TABLE analysis_jobs ADD COLUMN trigger varchar(10) NOT NULL DEFAULT 'USER';
ALTER TABLE analysis_jobs ADD COLUMN retry_count integer NOT NULL DEFAULT 0;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_trigger CHECK (trigger IN ('USER', 'AUTO', 'RETRY', 'ADMIN'));

-- 자동 잡 생성은 5초마다 "최근에 사진이 올라온 갤러리"를 찾는다. 사진 전체가 아니라 최근 올라온 것만 읽게 한다.
CREATE INDEX idx_photos_uploaded_at
    ON photos (uploaded_at)
    WHERE status = 'UPLOADED' AND deleted_at IS NULL;
