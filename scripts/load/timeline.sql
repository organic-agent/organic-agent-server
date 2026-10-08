-- 부하 측정 타임라인 — 갤러리마다 업로드 시작(T0)부터 AI 폴더 완료(T_done)까지 단계별 경과 시간.
-- 계획: docs/plans/2026-10-06/concurrent-upload-load/02-baseline.md 1장. 읽기 전용.
--
--   psql ... -v ids='{101,102}' < scripts/load/timeline.sql
--
-- 단계 칸은 T0(그 갤러리에서 처음 발급된 업로드 URL = min(photos.created_at)) 기준 경과(분:초)다.
-- "업로드 뒤"는 마지막 업로드 → 완료 — 회선과 무관하게 서버·AI가 쓴 몫이다. 하루를 넘는 칸은 "1일+"(재분석 등).
WITH p AS (
  SELECT p.gallery_id, min(p.created_at) t0, max(p.uploaded_at) t_up, count(*) photos,
         max(a.created_at) t_embed, max(a.analyzed_at) t_score, count(a.error) errors,
         sum(p.byte_size) bytes
  FROM photos p LEFT JOIN photo_analysis a ON a.photo_id = p.id
  WHERE p.gallery_id = ANY(:'ids'::bigint[]) AND p.deleted_at IS NULL
  GROUP BY p.gallery_id
), j AS (
  SELECT DISTINCT ON (gallery_id) id, gallery_id, created_at t_job, categorizing_at t_cat, status, finished_at
  FROM analysis_jobs WHERE gallery_id = ANY(:'ids'::bigint[]) ORDER BY gallery_id, id DESC
), t AS (
  -- DONE 이벤트(V31 이후)가 없으면 DONE 잡의 finished_at 으로 대신한다.
  SELECT p.*, j.status, j.t_job, j.t_cat,
         coalesce((SELECT min(e.created_at) FROM analysis_job_events e WHERE e.job_id = j.id AND e.type = 'DONE'),
                  CASE WHEN j.status = 'DONE' THEN j.finished_at END) t_done,
         -- categorize Lambda 가 배정 행을 쓴 마지막 시각 = AI 가 결과를 낸 시각. 완료와의 차이가 wes 의 폴더 물질화 몫.
         (SELECT max(c.created_at) FROM concept_assignments c WHERE c.job_id = j.id) t_cat_end,
         (SELECT count(*) FROM analysis_job_events e WHERE e.job_id = j.id AND e.type = 'SCORE_FALLBACK') fallbacks
  FROM p LEFT JOIN j USING (gallery_id)
), f AS (
  SELECT gallery_id, photos, errors, status, fallbacks,
         -- 업로드 평균 속도 = 원본 바이트 합 × 8 / (마지막 업로드 − T0). 회선 속도의 하한 어림.
         round(bytes * 8 / 1e6 / nullif(extract(epoch FROM t_up - t0), 0))::int mbps,
         array_agg(CASE WHEN x IS NULL THEN '—'
                        WHEN x >= interval '1 day' THEN '1일+'
                        ELSE floor(extract(epoch FROM x) / 60)::int || ':' || lpad((floor(extract(epoch FROM x))::int % 60)::text, 2, '0')
                   END ORDER BY n) v
  FROM t, unnest(ARRAY[t_up - t0, t_embed - t0, t_score - t0, t_job - t0, t_cat - t0, t_cat_end - t0, t_done - t0, t_done - t_up])
          WITH ORDINALITY u(x, n)
  GROUP BY gallery_id, photos, errors, status, fallbacks, bytes, t_up, t0
)
SELECT gallery_id AS 갤러리, photos AS 사진, errors AS 오류, status AS 잡,
       v[1] AS 업로드, mbps AS "Mbps", v[2] AS 임베딩, v[3] AS 점수, v[4] AS 잡생성, v[5] AS cat보냄, v[6] AS cat끝, v[7] AS 완료, v[8] AS "업로드 뒤",
       fallbacks AS 폴백
FROM f ORDER BY gallery_id;
