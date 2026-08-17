package com.soma.wes.trash.repository

import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.trash.repository.projection.TrashedGalleryRow
import com.soma.wes.trash.repository.projection.TrashedPhotoRow
import com.soma.wes.trash.repository.projection.TrashedPhotoTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * 휴지통이 다루는 행 — `deleted_at`이 채워진 사진·갤러리 — 의 유일한 통로.
 *
 * JPA가 아니라 [JdbcClient]인 이유는 엔티티의 `@SQLRestriction`이다. 휴지통 행은 어떤 JPA
 * 조회에도 나타나지 않으므로(그것이 그 애노테이션의 존재 이유다), 목록·복원·물리 삭제는
 * 필터를 우회하는 네이티브 SQL이어야 한다. 반대로 이 클래스 밖의 네이티브 SQL은
 * `deleted_at IS NULL`을 직접 챙겨야 한다.
 *
 * 복원의 `deleted_at IS NOT NULL` 조건은 검증을 겸한다 — 갱신된 행 수가 요청과 다르면
 * 서비스가 예외를 던져 트랜잭션째 되돌린다.
 */
@Repository
class TrashRepository(
    private val jdbcClient: JdbcClient,
) {

    // --- 갤러리 ---

    fun findTrashedGalleries(studioId: Long): List<TrashedGalleryRow> =
        jdbcClient.sql(
            """
            SELECT g.id, g.title, g.deleted_at,
                   (SELECT count(*) FROM photos p WHERE p.gallery_id = g.id) AS photo_count
            FROM galleries g
            WHERE g.studio_id = :studioId
              AND g.deleted_at IS NOT NULL
            ORDER BY g.deleted_at DESC, g.id DESC
            """.trimIndent(),
        )
            .param("studioId", studioId)
            .query { rs, _ ->
                TrashedGalleryRow(
                    galleryId = rs.getLong("id"),
                    title = rs.getString("title"),
                    photoCount = rs.getLong("photo_count"),
                    deletedAt = zonedDateTimeOf(rs, "deleted_at"),
                )
            }
            .list()

    /** 스튜디오 조건이 곧 인가다 — 갤러리가 숨어 있어 GalleryAccessPolicy를 지날 수 없다. */
    fun isTrashedGalleryOf(galleryId: Long, studioId: Long): Boolean =
        jdbcClient.sql(
            "SELECT count(*) FROM galleries WHERE id = :galleryId AND studio_id = :studioId AND deleted_at IS NOT NULL",
        )
            .param("galleryId", galleryId)
            .param("studioId", studioId)
            .query { rs, _ -> rs.getLong(1) }
            .single() > 0

    fun restoreGallery(galleryId: Long, studioId: Long): Int =
        jdbcClient.sql(
            """
            UPDATE galleries
            SET deleted_at = NULL
            WHERE id = :galleryId
              AND studio_id = :studioId
              AND deleted_at IS NOT NULL
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("studioId", studioId)
            .update()

    /** FK cascade가 사진·멤버·초대·폴더·앨범·협업까지 한 문장으로 걷는다(V9·V12). */
    fun deleteGallery(galleryId: Long): Int =
        jdbcClient.sql("DELETE FROM galleries WHERE id = :galleryId")
            .param("galleryId", galleryId)
            .update()

    fun findExpiredGalleryIds(cutoff: ZonedDateTime): List<Long> =
        jdbcClient.sql("SELECT id FROM galleries WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff ORDER BY id")
            .param("cutoff", cutoff.toOffsetDateTime())
            .query { rs, _ -> rs.getLong("id") }
            .list()

    // --- 사진 ---

    fun findTrashedPhotos(galleryId: Long): List<TrashedPhotoRow> =
        jdbcClient.sql(
            """
            SELECT id, original_file_name, status, storage_key, preview_key, deleted_at
            FROM photos
            WHERE gallery_id = :galleryId
              AND deleted_at IS NOT NULL
            ORDER BY deleted_at DESC, id DESC
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .query { rs, _ ->
                TrashedPhotoRow(
                    photoId = rs.getLong("id"),
                    originalFileName = rs.getString("original_file_name"),
                    status = PhotoStatus.valueOf(rs.getString("status")),
                    storageKey = rs.getString("storage_key"),
                    previewKey = rs.getString("preview_key"),
                    deletedAt = zonedDateTimeOf(rs, "deleted_at"),
                )
            }
            .list()

    /**
     * 즉시 물리 삭제 대상. 갤러리와 휴지통 여부로 좁히므로, 돌아온 수가 요청한 수보다
     * 적다는 것이 곧 "휴지통에 없는 id가 섞였다"는 뜻이다.
     */
    fun findTrashedPhotoTargets(galleryId: Long, photoIds: Collection<Long>): List<TrashedPhotoTarget> =
        jdbcClient.sql(
            """
            SELECT id, storage_key, preview_key, upload_url_expires_at
            FROM photos
            WHERE gallery_id = :galleryId
              AND id IN (:photoIds)
              AND deleted_at IS NOT NULL
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("photoIds", photoIds)
            .query { rs, _ -> photoTarget(rs) }
            .list()

    /** 갤러리 물리 삭제용 — 휴지통에 있든 살아 있든 이 갤러리의 모든 사진. */
    fun findAllPhotoTargets(galleryId: Long): List<TrashedPhotoTarget> =
        jdbcClient.sql(
            "SELECT id, storage_key, preview_key, upload_url_expires_at FROM photos WHERE gallery_id = :galleryId",
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> photoTarget(rs) }
            .list()

    fun findExpiredPhotoTargets(cutoff: ZonedDateTime): List<TrashedPhotoTarget> =
        jdbcClient.sql(
            """
            SELECT id, storage_key, preview_key, upload_url_expires_at
            FROM photos
            WHERE deleted_at IS NOT NULL
              AND deleted_at < :cutoff
            ORDER BY id
            """.trimIndent(),
        )
            .param("cutoff", cutoff.toOffsetDateTime())
            .query { rs, _ -> photoTarget(rs) }
            .list()

    fun restorePhotos(galleryId: Long, photoIds: Collection<Long>): Int =
        jdbcClient.sql(
            """
            UPDATE photos
            SET deleted_at = NULL
            WHERE gallery_id = :galleryId
              AND id IN (:photoIds)
              AND deleted_at IS NOT NULL
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("photoIds", photoIds)
            .update()

    /** FK cascade가 별점·폴더 항목·앨범 항목·협업 사진(과 그 댓글·투표)을 함께 걷는다. */
    fun deletePhotos(photoIds: Collection<Long>): Int =
        jdbcClient.sql("DELETE FROM photos WHERE id IN (:photoIds)")
            .param("photoIds", photoIds)
            .update()

    // --- 보정 파일 ---

    /**
     * 갤러리 물리 삭제 전에 걷는 보정 파일(주석·결과)의 key. `retouch_photos` 행은 FK
     * cascade로 함께 지워지므로, 행이 사라지기 전에 key를 걷지 않으면 아무도 그 객체를
     * 지울 수 없다.
     */
    fun findRetouchObjectKeys(galleryId: Long): List<String> =
        jdbcClient.sql(
            """
            SELECT annotation_key, result_key
            FROM retouch_photos
            WHERE gallery_id = :galleryId
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> listOfNotNull(rs.getString("annotation_key"), rs.getString("result_key")) }
            .list()
            .flatten()

    /** 사진 물리 삭제용 — 지워질 사진들에 딸린 보정 파일의 key. */
    fun findRetouchObjectKeysByPhotoIds(photoIds: Collection<Long>): List<String> =
        jdbcClient.sql(
            """
            SELECT annotation_key, result_key
            FROM retouch_photos
            WHERE photo_id IN (:photoIds)
            """.trimIndent(),
        )
            .param("photoIds", photoIds)
            .query { rs, _ -> listOfNotNull(rs.getString("annotation_key"), rs.getString("result_key")) }
            .list()
            .flatten()

    private fun photoTarget(rs: ResultSet): TrashedPhotoTarget =
        TrashedPhotoTarget(
            photoId = rs.getLong("id"),
            storageKey = rs.getString("storage_key"),
            previewKey = rs.getString("preview_key"),
            uploadUrlExpiresAt = rs.getObject("upload_url_expires_at", OffsetDateTime::class.java)?.toInstant(),
        )

    private fun zonedDateTimeOf(rs: ResultSet, column: String): ZonedDateTime =
        rs.getObject(column, OffsetDateTime::class.java).toZonedDateTime()
}
