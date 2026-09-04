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
        clusterId: Int = 1,
        clusterRank: Int = 0,
        technicalPct: Double = 80.0,
        aestheticPct: Double = 70.0,
        sharpness: Double? = null,
    ) {
        val subScores = if (sharpness == null) "{}" else "{\"sharpness\": $sharpness}"
        jdbcTemplate.update(
            """
            UPDATE photo_analysis
            SET embed_group_id = ?, subjects = ?, technical_pct = ?, aesthetic_pct = ?,
                cluster_id = ?, cluster_rank = ?, sub_scores = ?::jsonb,
                model_version = 'test-v1', analyzed_at = now(), updated_at = now()
            WHERE photo_id = ?
            """.trimIndent(),
            embedGroupId, subjects, technicalPct, aestheticPct, clusterId, clusterRank, subScores, photoId,
        )
    }

    /** 임베더가 미리보기를 만들고 photos.preview_key를 채운 상태. 판정·이유가 LLM에 사진을 보내는 전제다. */
    fun 미리보기(photoId: Long, previewKey: String = "previews/$photoId.jpg") {
        jdbcTemplate.update("UPDATE photos SET preview_key = ?, updated_at = now() WHERE id = ?", previewKey, photoId)
    }

    /** 분석 배치가 남긴 갤러리 분석 잡. 기본은 DONE — 폴더 생성의 전제다. */
    fun 분석_잡(galleryId: Long, mode: String = "FULL", status: String = "DONE"): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO ai_analysis_jobs (gallery_id, mode, status, started_at, finished_at, created_at, updated_at)
            VALUES (?, ?, ?, now(), now(), now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            galleryId, mode, status,
        )!!

    /** AI 워커가 적재하는 추천 행. reason은 2단계라 기본 null이다. */
    fun 추천(
        selectionId: Long,
        photoId: Long,
        round: Int = 1,
        rank: Int = 1,
        folderId: Long? = null,
        reason: String? = null,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO ai_recommendations
                (selection_id, photo_id, round, rank, folder_id, reason, presented_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now(), now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            selectionId, photoId, round, rank, folderId, reason,
        )!!

    /** 워커의 이유 2단계 UPDATE. `reasonReady`가 뒤집히는 경로다. */
    fun 추천_이유(selectionId: Long, photoId: Long, round: Int, reason: String) {
        jdbcTemplate.update(
            "UPDATE ai_recommendations SET reason = ?, updated_at = now() WHERE selection_id = ? AND round = ? AND photo_id = ?",
            reason, selectionId, round, photoId,
        )
    }

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
        parentName: String,
        conceptName: String,
        needsReview: Boolean = false,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO ai_concept_assignments
                (job_id, gallery_id, embed_group_id, parent_name, concept_name, confidence, assigned_by,
                 needs_review, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 0.9, 'vlm', ?, now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            jobId, galleryId, embedGroupId, parentName, conceptName, needsReview,
        )!!
}
