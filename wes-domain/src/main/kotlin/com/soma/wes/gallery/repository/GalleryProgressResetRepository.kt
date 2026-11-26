package com.soma.wes.gallery.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

/**
 * 여러 도메인의 테이블을 갤러리 하나 범위로 한꺼번에 지우고, 휴지통에 간(`deleted_at`) 사진의 기록까지 지워야 해서 JDBC를 쓴다.
 *
 * 부모 행만 지우면 자식은 FK `ON DELETE CASCADE`가 함께 지운다 — 셀렉(→ 담은 사진·추천·AI 셀렉 잡),
 * 협업 링크(→ 참여자·좋아요·댓글·공유 사진), 보정 회차(→ 보정 사진), 컨셉 폴더(→ 세부 폴더·배정), 폴더 합치기(→ 옮긴 사진).
 * 사진·AI 분석(`photo_analysis`·`analysis_jobs`·`concept_assignments`)은 남긴다. 멤버·초대는 [deleteMembership]을 따로 불렀을 때만 지운다.
 *
 * 호출자가 갤러리 행을 잠근 트랜잭션 안에서만 부른다. JPA로 읽어 둔 같은 갤러리의 자식 엔티티가 있으면 낡는다 —
 * 호출자는 이 뒤에 그 엔티티를 다시 만지지 않는다.
 */
@Repository
class GalleryProgressResetRepository(
    private val jdbc: JdbcClient,
) {

    companion object {

        /** 사진에 바로 달린 사용자 기록. 갤러리 컬럼이 없어 사진으로 찾는다. */
        private val PHOTO_SCOPED_TABLES = listOf(
            "photo_ratings",
            "photo_rating_history",
            "photo_memos",
            "photo_comments",
            "ai_recommendations",
        )

        /** 갤러리에 달린 작업의 부모 행. 자식은 CASCADE로 함께 지워진다. */
        private val GALLERY_SCOPED_TABLES = listOf(
            "collab_sessions",
            "photo_selections",
            "retouch_rounds",
            "gallery_albums",
            "detail_folder_merges",
            "concept_folders",
            "gallery_activity",
        )

        /** 갤러리에 들어온 사람과 들어올 권한. 관리자 초기화에서만 지운다. */
        private val MEMBERSHIP_TABLES = listOf(
            "gallery_members",
            "gallery_invites",
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteProgress(galleryId: Long, now: ZonedDateTime) {
        PHOTO_SCOPED_TABLES.forEach { table ->
            jdbc.sql("DELETE FROM $table WHERE photo_id IN (SELECT id FROM photos WHERE gallery_id = :galleryId)")
                .param("galleryId", galleryId)
                .update()
        }
        GALLERY_SCOPED_TABLES.forEach { table ->
            jdbc.sql("DELETE FROM $table WHERE gallery_id = :galleryId")
                .param("galleryId", galleryId)
                .update()
        }
        jdbc.sql(
            """
            UPDATE photos SET excluded_at = NULL, excluded_by = NULL, updated_at = :now, version = version + 1
            WHERE gallery_id = :galleryId AND excluded_at IS NOT NULL
            """.trimIndent(),
        ).param("galleryId", galleryId)
            .param("now", now.toOffsetDateTime())
            .update()
    }

    /**
     * 갤러리 멤버(초대를 수락한 부부·파트너)와 초대를 지운다. 갤러리 주인은 작업공간 소유자라 이 행에 없어 그대로 남는다.
     * 탈퇴 표시(`deleted_at`)가 아니라 행을 지운다 — "아무도 들어오지 않은 갤러리"로 되돌리는 관리자 초기화라서다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteMembership(galleryId: Long) {
        MEMBERSHIP_TABLES.forEach { table ->
            jdbc.sql("DELETE FROM $table WHERE gallery_id = :galleryId")
                .param("galleryId", galleryId)
                .update()
        }
    }
}
