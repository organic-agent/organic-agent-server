package com.soma.wes.trash.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.ZonedDateTime

/**
 * 제품 API에서 일어난 자식 자원 삭제를 관리자 7일 휴지통 계약에 연결한다.
 *
 * 이 저장소는 관리자 웹/API 타입에 의존하지 않는다. 다만 두 실행 앱이 공유하는
 * `admin_child_trash_records` DB 계약에 고정된 자원·행위자·사유 코드만 남긴다.
 */
@Repository
class ProductChildTrashRepository(
    private val jdbcClient: JdbcClient,
) {

    fun lockActiveCollaboration(sessionId: Long): Boolean = jdbcClient.sql(
        "SELECT id FROM collab_sessions WHERE id = :sessionId AND deleted_at IS NULL FOR UPDATE",
    )
        .param("sessionId", sessionId)
        .query { rs, _ -> rs.getLong("id") }
        .optional()
        .isPresent

    fun lockActiveRetouchRound(roundId: Long): Boolean = jdbcClient.sql(
        "SELECT id FROM retouch_rounds WHERE id = :roundId AND deleted_at IS NULL FOR UPDATE",
    )
        .param("roundId", roundId)
        .query { rs, _ -> rs.getLong("id") }
        .optional()
        .isPresent

    /** 좋아요 생성과 동일한 사진 행을 잠궈 재좋아요와 취소가 교차하지 않게 한다. */
    fun lockCollabPhoto(sessionId: Long, photoId: Long): Boolean = jdbcClient.sql(
        """
        SELECT p.id
        FROM collab_sessions s
        JOIN photos p ON p.gallery_id = s.gallery_id AND p.deleted_at IS NULL
        WHERE s.id = :sessionId AND s.deleted_at IS NULL AND p.id = :photoId
          AND (
              EXISTS (
                  SELECT 1 FROM detail_folders d
                  JOIN detail_folder_assignments a ON a.detail_folder_id = d.id
                  WHERE d.concept_folder_id = s.concept_folder_id AND d.deleted_at IS NULL
                    AND a.photo_id = p.id
              ) OR (s.concept_folder_id IS NULL AND EXISTS (
                  SELECT 1 FROM collab_session_photos membership
                  WHERE membership.collab_session_id = s.id AND membership.photo_id = p.id
              ))
          )
        FOR UPDATE OF p
        """.trimIndent(),
    )
        .param("photoId", photoId)
        .param("sessionId", sessionId)
        .query { rs, _ -> rs.getLong("id") }
        .optional()
        .isPresent

    fun softDeleteComment(
        sessionId: Long,
        commentId: Long,
        authorParticipantId: Long?,
        deletedAt: ZonedDateTime,
    ): Long? {
        val authorPredicate = authorParticipantId?.let { "AND c.participant_id = :authorParticipantId" }.orEmpty()
        var statement = jdbcClient.sql(
            """
            UPDATE collab_photo_comments c
            SET deleted_at = :deletedAt, version = c.version + 1, updated_at = :deletedAt
            WHERE c.id = :commentId AND c.deleted_at IS NULL
              AND c.collab_session_id = :sessionId
              $authorPredicate
            RETURNING c.id
            """.trimIndent(),
        )
            .param("commentId", commentId)
            .param("sessionId", sessionId)
            .param("deletedAt", deletedAt.toOffsetDateTime())
        if (authorParticipantId != null) statement = statement.param("authorParticipantId", authorParticipantId)
        return statement.query { rs, _ -> rs.getLong("id") }.optional().orElse(null)
    }

    fun softDeleteLike(
        sessionId: Long,
        photoId: Long,
        participantId: Long,
        deletedAt: ZonedDateTime,
    ): Long? = jdbcClient.sql(
        """
        UPDATE collab_photo_likes l
        SET deleted_at = :deletedAt, version = l.version + 1, updated_at = :deletedAt
        WHERE l.collab_session_id = :sessionId
          AND l.photo_id = :photoId
          AND l.participant_id = :participantId
          AND l.deleted_at IS NULL
        RETURNING l.id
        """.trimIndent(),
    )
        .param("sessionId", sessionId)
        .param("photoId", photoId)
        .param("participantId", participantId)
        .param("deletedAt", deletedAt.toOffsetDateTime())
        .query { rs, _ -> rs.getLong("id") }
        .optional()
        .orElse(null)

    fun softDeleteRetouchItem(roundId: Long, photoId: Long, deletedAt: ZonedDateTime): Long? = jdbcClient.sql(
        """
        UPDATE retouch_photos item
        SET deleted_at = :deletedAt, version = item.version + 1, updated_at = :deletedAt
        WHERE item.round_id = :roundId AND item.photo_id = :photoId AND item.deleted_at IS NULL
        RETURNING item.id
        """.trimIndent(),
    )
        .param("roundId", roundId)
        .param("photoId", photoId)
        .param("deletedAt", deletedAt.toOffsetDateTime())
        .query { rs, _ -> rs.getLong("id") }
        .optional()
        .orElse(null)

    fun bumpCollaborationVersion(sessionId: Long, changedAt: ZonedDateTime): Int = jdbcClient.sql(
        """
        UPDATE collab_sessions
        SET version = version + 1, updated_at = :changedAt
        WHERE id = :sessionId AND deleted_at IS NULL
        """.trimIndent(),
    )
        .param("sessionId", sessionId)
        .param("changedAt", changedAt.toOffsetDateTime())
        .update()

    fun bumpRetouchRoundVersion(roundId: Long, changedAt: ZonedDateTime): Int = jdbcClient.sql(
        """
        UPDATE retouch_rounds
        SET version = version + 1, updated_at = :changedAt
        WHERE id = :roundId AND deleted_at IS NULL
        """.trimIndent(),
    )
        .param("roundId", roundId)
        .param("changedAt", changedAt.toOffsetDateTime())
        .update()

    fun createRecord(
        resourceType: String,
        resourceId: Long,
        parentType: String,
        parentId: Long,
        actorLabel: String,
        reasonCode: String,
        deletedAt: ZonedDateTime,
        restoreUntil: ZonedDateTime,
    ): Long = jdbcClient.sql(
        """
        INSERT INTO admin_child_trash_records (
            resource_type, resource_id, parent_type, parent_id, actor_admin_id,
            actor_username, reason, status, deleted_at, restore_until, created_at, updated_at
        )
        VALUES (
            :resourceType, :resourceId, :parentType, :parentId, NULL,
            :actorLabel, :reasonCode, 'ACTIVE', :deletedAt, :restoreUntil, :deletedAt, :deletedAt
        )
        RETURNING id
        """.trimIndent(),
    )
        .param("resourceType", resourceType)
        .param("resourceId", resourceId)
        .param("parentType", parentType)
        .param("parentId", parentId)
        .param("actorLabel", actorLabel)
        .param("reasonCode", reasonCode)
        .param("deletedAt", deletedAt.toOffsetDateTime())
        .param("restoreUntil", restoreUntil.toOffsetDateTime())
        .query { rs, _ -> rs.getLong("id") }
        .single()
}
