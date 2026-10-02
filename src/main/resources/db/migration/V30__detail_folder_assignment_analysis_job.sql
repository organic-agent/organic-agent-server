-- AI 배정 행에 "어느 분석 잡이 넣었는지"를 적는다.
--
-- 물질화의 멱등 검사는 "이 잡이 만든 컨셉 폴더가 있나"였다. 나눠 올린 사진을 기존 폴더에 합치기만 하는 잡(#217)은
-- 컨셉 폴더를 만들지 않아 표식이 남지 않았고, 같은 잡으로 다시 부르면 "넣을 사진 없음"으로 끝났다.
-- 배정 행은 합치든 새로 만들든 반드시 생기므로 표식을 여기에 둔다.
--
-- NULL 은 사용자가 직접 넣은 배정이거나 이 컬럼이 생기기 전의 AI 배정이다(그 시절 잡은 컨셉 폴더의 analysis_job_id 로 식별한다).
-- 사용자가 사진을 다른 폴더로 옮겨도 값은 남는다 — "처음 넣은 잡"의 기록이다.
ALTER TABLE detail_folder_assignments ADD COLUMN analysis_job_id bigint;
ALTER TABLE detail_folder_assignments
    ADD CONSTRAINT fk_detail_folder_assignments_analysis_job
    FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE SET NULL;
-- 잡 하나의 배정을 찾는 조회와, 잡 삭제 때 SET NULL 이 전체 스캔하지 않게 하는 인덱스.
CREATE INDEX idx_detail_folder_assignments_analysis_job
    ON detail_folder_assignments (analysis_job_id) WHERE analysis_job_id IS NOT NULL;
