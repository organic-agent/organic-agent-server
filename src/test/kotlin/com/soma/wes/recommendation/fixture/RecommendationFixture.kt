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
    ) {
        jdbcTemplate.update(
            """
            UPDATE photo_analysis
            SET embed_group_id = ?, subjects = ?, technical_pct = 80.0, aesthetic_pct = 70.0,
                cluster_id = ?, cluster_rank = ?,
                model_version = 'test-v1', analyzed_at = now(), updated_at = now()
            WHERE photo_id = ?
            """.trimIndent(),
            embedGroupId, subjects, clusterId, clusterRank, photoId,
        )
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
