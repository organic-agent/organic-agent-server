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
 */
@Repository
class AgentPhotoQueryRepository(
    private val jdbcClient: JdbcClient,
) {

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
                   ) AS selected
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
        )

    private fun ResultSet.getNullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.getNullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }
}
