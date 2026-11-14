package com.soma.wes.agent.repository

import com.soma.wes.agent.dto.PhotoReactionsDto
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * 거른 사진들의 반응 본문(별점·메모·댓글·좋아요한 사람)을 읽는다 — "가족이 좋아한 사진만"처럼 반응으로 거른 결과에 같이 싣는다(#348 ③).
 * 갤러리 전체 읽기([AgentPhotoQueryRepository])에 본문까지 얹으면 7천 장 × 문장이라, 조건에 걸린 사진만 따로 읽는다.
 * 댓글은 부부 댓글(`photo_comments`, 닉네임)과 하객 댓글(`collab_photo_comments`, 이름표)을 합쳐 최근 것부터 사진당 몇 개만.
 */
@Repository
class AgentReactionQueryRepository(
    private val jdbcClient: JdbcClient,
) {

    companion object {

        const val MAX_COMMENTS_PER_PHOTO = 3
    }

    fun findByPhotoIds(photoIds: Collection<Long>): List<PhotoReactionsDto> {
        if (photoIds.isEmpty()) return emptyList()
        val ratingAndMemo = jdbcClient.sql(
            """
            SELECT p.id AS photo_id, r.score, m.content
            FROM photos p
            LEFT JOIN photo_ratings r ON r.photo_id = p.id
            LEFT JOIN photo_memos m ON m.photo_id = p.id
            WHERE p.id IN (:ids) AND (r.photo_id IS NOT NULL OR m.photo_id IS NOT NULL)
            """.trimIndent(),
        ).param("ids", photoIds).query { rs, _ -> Triple(rs.getLong("photo_id"), rs.getInt("score").takeUnless { rs.wasNull() }, rs.getString("content")) }.list()

        val comments = jdbcClient.sql(
            """
            SELECT photo_id, author, content FROM (
                SELECT c.photo_id, COALESCE(u.nickname, cp.nickname, '하객') AS author, c.content, c.created_at,
                       row_number() OVER (PARTITION BY c.photo_id ORDER BY c.created_at DESC) AS rn
                FROM (
                    SELECT photo_id, author_id AS user_id, NULL::bigint AS participant_id, content, created_at
                    FROM photo_comments WHERE photo_id IN (:ids) AND deleted_at IS NULL
                    UNION ALL
                    SELECT photo_id, NULL, participant_id, content, created_at
                    FROM collab_photo_comments WHERE photo_id IN (:ids) AND deleted_at IS NULL
                ) c
                LEFT JOIN users u ON u.id = c.user_id
                LEFT JOIN collab_participants cp ON cp.id = c.participant_id
            ) x WHERE rn <= :limit
            """.trimIndent(),
        ).param("ids", photoIds).param("limit", MAX_COMMENTS_PER_PHOTO)
            .query { rs, _ -> Triple(rs.getLong("photo_id"), rs.getString("author"), rs.getString("content")) }.list()

        val likes = jdbcClient.sql(
            """
            SELECT k.photo_id, cp.nickname
            FROM collab_photo_likes k
            JOIN collab_participants cp ON cp.id = k.participant_id
            JOIN collab_sessions cs ON cs.id = k.collab_session_id AND cs.deleted_at IS NULL
            WHERE k.photo_id IN (:ids) AND k.deleted_at IS NULL
            ORDER BY k.created_at
            """.trimIndent(),
        ).param("ids", photoIds).query { rs, _ -> rs.getLong("photo_id") to rs.getString("nickname") }.list()

        val commentsByPhoto = comments.groupBy({ it.first }, { PhotoReactionsDto.Comment(author = it.second, content = it.third) })
        val likesByPhoto = likes.groupBy({ it.first }, { it.second })
        val ratingMemoByPhoto = ratingAndMemo.associateBy { it.first }
        val touched = (commentsByPhoto.keys + likesByPhoto.keys + ratingMemoByPhoto.keys)
        val order = photoIds.withIndex().associate { it.value to it.index }
        return touched.sortedBy { order[it] }.map { photoId ->
            PhotoReactionsDto(
                photoId = photoId,
                rating = ratingMemoByPhoto[photoId]?.second,
                memo = ratingMemoByPhoto[photoId]?.third,
                comments = commentsByPhoto[photoId].orEmpty(),
                likedBy = likesByPhoto[photoId].orEmpty(),
            )
        }
    }
}
