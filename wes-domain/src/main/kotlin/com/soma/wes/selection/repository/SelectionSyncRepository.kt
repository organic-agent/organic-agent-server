package com.soma.wes.selection.repository

import com.soma.wes.selection.repository.projection.SelectionSyncRevision
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** 엔티티 없이 DB 트리거가 관리하는 변경 번호를 읽으므로 JDBC를 쓴다. 쓰기 트랜잭션과 같은 DB가 서버 사이의 기준이다. */
@Repository
class SelectionSyncRepository(
    private val jdbc: JdbcClient,
) {
    fun findByGalleryId(galleryId: Long): SelectionSyncRevision = jdbc.sql(
        """
        SELECT selection_revision, rating_revision, exclusion_revision
        FROM gallery_selection_sync WHERE gallery_id = :galleryId
        """.trimIndent(),
    ).param("galleryId", galleryId)
        .query { rs, _ ->
            SelectionSyncRevision(
                selectionRevision = rs.getLong("selection_revision"),
                ratingRevision = rs.getLong("rating_revision"),
                exclusionRevision = rs.getLong("exclusion_revision"),
            )
        }.optional().orElse(SelectionSyncRevision(selectionRevision = 0, ratingRevision = 0))
}
