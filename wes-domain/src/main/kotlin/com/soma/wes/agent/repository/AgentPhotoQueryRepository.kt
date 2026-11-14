package com.soma.wes.agent.repository

import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.PhotoOrientation
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import com.soma.wes.photo.domain.PhotoStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * 도우미가 보는 갤러리 사진 전부를 한 번에 읽는다.
 *
 * 질문마다 조건이 달라 조건별 SQL을 만들지 않고, 사진 행을 모두 읽어 서버에서 거르고 센다 — 7천 장 갤러리도
 * 한 번의 읽기(수십 ms)라 판단 한 번(약 1.5초)에 비해 작다. 업로드가 끝나고 휴지통에 가지 않은 사진만 센다.
 *
 * 반응(좋아요·댓글·별점·메모)과 누가 했는지(담기·별점·메모·댓글)도 사진당 서브쿼리 하나씩이다 — 전부 `photo_id` 인덱스 탐색이라 7천 장에 수십 ms 안이다.
 * 댓글은 부부 댓글(`photo_comments`)과 하객 댓글(`collab_photo_comments`)을 합쳐 센다 — "댓글 달린 사진"은 누가 남겼든 같은 뜻이다.
 * 좋아요는 휴지통(`deleted_at`)에 든 것과 지운 세션의 것을 빼고 센다. 만료·회수된 세션의 좋아요는 남긴다 — 가족이 남긴 마음은 세션이 닫혀도 유효하다.
 */
@Repository
class AgentPhotoQueryRepository(
    private val jdbcClient: JdbcClient,
) {

    companion object {

        /** `string_agg`로 모은 댓글 작성자 id의 구분자. 작성자는 사진당 많아야 몇 명이라 배열 대신 문자열로 읽는다. */
        private const val AUTHOR_SEPARATOR = ","
    }

    fun findRows(galleryId: Long): List<AgentPhotoRow> =
        jdbcClient.sql(
            """
            SELECT p.id AS photo_id,
                   df.concept_folder_id,
                   df.id AS detail_folder_id,
                   a.subjects,
                   p.width,
                   p.height,
                   a.burst_id,
                   a.burst_rank,
                   p.taken_at,
                   EXISTS (
                       SELECT 1 FROM photo_selection_items i
                       WHERE i.gallery_id = p.gallery_id AND i.photo_id = p.id
                   ) AS selected,
                   (
                       SELECT i.added_by_user_id FROM photo_selection_items i
                       WHERE i.gallery_id = p.gallery_id AND i.photo_id = p.id
                       LIMIT 1
                   ) AS added_by,
                   (
                       SELECT count(*) FROM collab_photo_likes k
                       JOIN collab_sessions cs ON cs.id = k.collab_session_id AND cs.deleted_at IS NULL
                       WHERE k.photo_id = p.id AND k.deleted_at IS NULL
                   ) AS like_count,
                   (SELECT count(*) FROM photo_comments c WHERE c.photo_id = p.id AND c.deleted_at IS NULL)
                   + (SELECT count(*) FROM collab_photo_comments gc WHERE gc.photo_id = p.id AND gc.deleted_at IS NULL) AS comment_count,
                   (SELECT r.score FROM photo_ratings r WHERE r.photo_id = p.id) AS rating,
                   (SELECT r.rated_by FROM photo_ratings r WHERE r.photo_id = p.id) AS rated_by,
                   EXISTS (SELECT 1 FROM photo_memos m WHERE m.photo_id = p.id) AS has_memo,
                   (SELECT m.updated_by FROM photo_memos m WHERE m.photo_id = p.id) AS memo_by,
                   (
                       SELECT string_agg(DISTINCT c.author_id::text, ',')
                       FROM photo_comments c WHERE c.photo_id = p.id AND c.deleted_at IS NULL
                   ) AS comment_authors
            FROM photos p
            LEFT JOIN photo_analysis a ON a.photo_id = p.id
            LEFT JOIN detail_folder_assignments x ON x.photo_id = p.id
            LEFT JOIN detail_folders df ON df.id = x.detail_folder_id AND df.deleted_at IS NULL
            WHERE p.gallery_id = :galleryId
              AND p.deleted_at IS NULL
              AND p.status = :uploaded
            ORDER BY p.display_order, p.id
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("uploaded", PhotoStatus.UPLOADED.name)
            .query { rs, _ -> rs.toRow() }
            .list()

    private fun ResultSet.toRow(): AgentPhotoRow =
        AgentPhotoRow(
            photoId = getLong("photo_id"),
            conceptFolderId = getNullableLong("concept_folder_id"),
            detailFolderId = getNullableLong("detail_folder_id"),
            subject = AgentSubject.fromAnalysis(getString("subjects")),
            orientation = PhotoOrientation.of(getNullableInt("width"), getNullableInt("height")),
            burstId = getNullableInt("burst_id"),
            burstRank = getNullableInt("burst_rank"),
            takenAt = getTimestamp("taken_at")?.toLocalDateTime(),
            selected = getBoolean("selected"),
            likeCount = getInt("like_count"),
            commentCount = getInt("comment_count"),
            rating = getNullableInt("rating"),
            hasMemo = getBoolean("has_memo"),
            addedByUserId = getNullableLong("added_by"),
            ratedByUserId = getNullableLong("rated_by"),
            memoByUserId = getNullableLong("memo_by"),
            commentAuthorIds = getString("comment_authors")?.split(AUTHOR_SEPARATOR)?.map { it.toLong() }?.toSet().orEmpty(),
        )

    private fun ResultSet.getNullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.getNullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }
}
