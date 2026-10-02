-- 분석 잡의 모든 대기에 끝을 둔다.
--
-- progress_count·progress_at: ANALYZING 의 진행 감시. 스윕이 갤러리의 진행 값(대상 + 임베딩 + 점수 장수)을 적어 두고,
--                             값이 바뀔 때마다 시각을 옮긴다. 시각이 오래 멈춰 있으면 잡이 멈춘 것이다.
-- categorizing_at:            CATEGORIZING 에 들어간 시각. dispatched_at 은 다시 보낼 때마다 옮겨지므로 잡 전체 기한의 기준이 못 된다.
-- materialize_attempts:       폴더 만들기가 예상 밖 예외로 실패한 횟수. 상한에서 잡을 닫는다.
-- error_code:                 FAILED 의 이유를 정해진 코드로. error 는 내부 문장 그대로 남고 응답에는 코드와 사용자 문장만 나간다.
--                             NULL 은 이 컬럼이 생기기 전에 닫힌 잡이다.
ALTER TABLE analysis_jobs ADD COLUMN progress_count integer;
ALTER TABLE analysis_jobs ADD COLUMN progress_at timestamp(6) with time zone;
ALTER TABLE analysis_jobs ADD COLUMN categorizing_at timestamp(6) with time zone;
ALTER TABLE analysis_jobs ADD COLUMN materialize_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE analysis_jobs ADD COLUMN error_code varchar(40);

-- 지금 CATEGORIZING 인 잡은 마지막으로 보낸 시각부터 기한을 센다(없으면 지금부터).
UPDATE analysis_jobs SET categorizing_at = COALESCE(dispatched_at, now()) WHERE status = 'CATEGORIZING';
