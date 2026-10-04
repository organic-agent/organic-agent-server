package com.soma.wes.recommendation.fixture

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * AI 쪽(분석 배치·`photoselect`)이 DB에 직접 쓰는 행을 흉내 낸다. 분석 컬럼과 배정은 서버에서
 * 읽기 전용이라 `save`로는 만들 수 없으므로 SQL로 넣는다 — 실제 쓰는 주체와 같은 길이다.
 */
@Component
class RecommendationFixture(
    private val jdbcTemplate: JdbcTemplate,
) {

    /** 분석 배치(full 잡)가 채우는 그룹·피사체·점수·클러스터. */
    fun 분석_결과(
        photoId: Long,
        embedGroupId: Int = 1,
        subjects: String = "couple",
        burstId: Int = 1,
        burstRank: Int = 0,
        technicalPct: Double = 80.0,
        aestheticPct: Double = 70.0,
        sharpness: Double? = null,
    ) {
        val subScores = if (sharpness == null) "{}" else "{\"sharpness\": $sharpness}"
        jdbcTemplate.update(
            """
            UPDATE photo_analysis
            SET embed_group_id = ?, subjects = ?, technical_pct = ?, aesthetic_pct = ?,
                burst_id = ?, burst_rank = ?, sub_scores = ?::jsonb,
                pipeline_version = 'test-v1', analyzed_at = now(), updated_at = now()
            WHERE photo_id = ?
            """.trimIndent(),
            embedGroupId, subjects, technicalPct, aestheticPct, burstId, burstRank, subScores, photoId,
        )
    }

    /** SCORE 잡만 끝난 상태 — `pipeline_version`은 있지만 CATEGORIZE가 채우는 백분위·그룹은 아직 비어 있다. */
    fun 점수만(photoId: Long, subjects: String = "couple") {
        jdbcTemplate.update(
            """
            UPDATE photo_analysis
            SET subjects = ?, sub_scores = '{"sharpness": 120.0}'::jsonb,
                pipeline_version = 'test-v1', analyzed_at = now(), updated_at = now()
            WHERE photo_id = ?
            """.trimIndent(),
            subjects, photoId,
        )
    }

    /** 임베더가 미리보기를 만들고 photos.preview_key를 채운 상태. 판정·이유가 LLM에 사진을 보내는 전제다. */
    fun 미리보기(photoId: Long, previewKey: String = "previews/$photoId.jpg") {
        jdbcTemplate.update("UPDATE photos SET preview_key = ?, updated_at = now() WHERE id = ?", previewKey, photoId)
    }

    /** 갤러리 분석 잡. 기본은 DONE — 폴더 생성의 전제다. 살아 있는 상태(ANALYZING·CATEGORIZING)는 갤러리당 하나뿐이다. */
    fun 분석_잡(galleryId: Long, status: String = "DONE"): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO analysis_jobs (gallery_id, status, finished_at, created_at, updated_at)
            VALUES (?, ?, CASE WHEN ? IN ('DONE', 'FAILED') THEN now() END, now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            galleryId, status, status,
        )!!

    /** 추천 실행기가 적재하는 추천 행. */
    fun 추천(
        selectionId: Long,
        photoId: Long,
        round: Int = 1,
        rank: Int = 1,
        folderId: Long? = null,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO ai_recommendations
                (selection_id, photo_id, round, rank, folder_id, presented_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, now(), now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            selectionId, photoId, round, rank, folderId,
        )!!

    /** 워커의 상태 전이 흉내 — 추천 잡을 닫는다. 살아 있는 잡 하나 규칙(부분 유니크)을 풀 때 쓴다. */
    fun 추천_잡_완료(jobId: Long, round: Int = 1) {
        jdbcTemplate.update(
            "UPDATE ai_selection_jobs SET status = 'DONE', round = ?, finished_at = now(), updated_at = now() WHERE id = ?",
            round, jobId,
        )
    }

    /** naming 잡이 남기는 컨셉 배정 한 건: 임베딩 그룹 → (큰 분류, 컨셉). */
    fun 컨셉_배정(
        jobId: Long,
        galleryId: Long,
        embedGroupId: Int,
        conceptName: String,
        detailName: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO concept_assignments
                (job_id, gallery_id, embed_group_id, concept_name, detail_name, confidence, assigned_by,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 0.9, 'vlm', now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            jobId, galleryId, embedGroupId, conceptName, detailName,
        )!!
}
