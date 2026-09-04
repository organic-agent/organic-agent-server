-- 분석 잡을 Lambda 셋(embedder → score → categorize)의 상태 기계로 만든다(#140). 바깥 status는 프론트가 보는 잡의 생애,
-- 안쪽 stage·stage_status는 지금 어느 Lambda 차례이며 어디까지 왔는지다. 누가 쓰는가:
--   wes    : stage · stage_attempts · dispatched_at · observed_progress · force, 잡을 닫는 DONE/FAILED
--   Lambda : stage_status 의 시작(claim)·끝, heartbeat_at, result 의 단계별 키, error
--            (단계 상태를 아직 쓰지 않는 Lambda 는 지금처럼 status 를 직접 RUNNING·DONE 으로 옮긴다 — wes 가 함께 다룬다)
--   EMBED  : 임베더는 잡을 모른다. wes 가 photo_analysis 를 관측해 단계를 열고 닫는다.
-- 배포 전에 살아 있는 잡(PENDING·RUNNING)이 없어야 한다. 있으면 stage 가 NULL 인 옛 잡으로 남고 wes 는 손대지 않는다.
ALTER TABLE ai_analysis_jobs
    ADD COLUMN stage             varchar(20),
    ADD COLUMN stage_status      varchar(20),
    ADD COLUMN stage_attempts    integer NOT NULL DEFAULT 0,
    ADD COLUMN dispatched_at     timestamp(6) with time zone,
    ADD COLUMN heartbeat_at      timestamp(6) with time zone,
    ADD COLUMN observed_progress integer NOT NULL DEFAULT 0,
    ADD COLUMN force             boolean NOT NULL DEFAULT false;

-- "임베딩만"(POST /embeddings/run)도 같은 잡의 한 모드가 된다.
ALTER TABLE ai_analysis_jobs DROP CONSTRAINT ck_ai_analysis_jobs_mode;
ALTER TABLE ai_analysis_jobs
    ADD CONSTRAINT ck_ai_analysis_jobs_mode CHECK (mode IN ('FULL', 'EMBED', 'NAMING')),
    ADD CONSTRAINT ck_ai_analysis_jobs_stage CHECK (stage IS NULL OR stage IN ('EMBED', 'SCORE', 'CATEGORIZE')),
    ADD CONSTRAINT ck_ai_analysis_jobs_stage_status CHECK (
        stage_status IS NULL OR stage_status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')
    );
