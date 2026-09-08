package com.soma.wes.photo.repository

import com.soma.wes.photo.dto.PendingPhotoDto
import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import java.sql.Timestamp
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * 사진 파이프라인의 집계·배치 갱신. 엔티티 루프가 아니라 SQL 한 문장씩이다 — 스윕이 5초마다 갤러리·사진 수천 장을
 * 상대하므로 왕복 수가 곧 비용이다.
 *
 * 전부 `deleted_at IS NULL`을 직접 건다(native SQL은 `@SQLRestriction` 밖이다). 분석 행은 임베더가 첫 배치에서
 * 만들므로 LEFT JOIN 해 없는 행을 "아직"으로 읽는다.
 */
@Repository
class PhotoPipelineRepository(
    private val jdbcClient: JdbcClient,
) {

    /** 갤러리 하나의 진행 카운트. [liveSince] 이후 만들어진 PENDING이 "아직 올라오는 중"이다. */
    fun progressOf(galleryId: Long, liveSince: ZonedDateTime): GalleryAnalysisProgress = jdbcClient.sql(
        """
        SELECT count(*)                                                                       AS total,
               count(*) FILTER (WHERE p.status = 'PENDING')                                   AS pending,
               count(*) FILTER (WHERE p.status = 'PENDING' AND p.created_at > :liveSince)     AS live_pending,
               count(*) FILTER (WHERE p.status = 'UPLOADED' AND a.error IS NOT NULL)          AS failed,
               count(*) FILTER (WHERE p.status = 'UPLOADED' AND a.error IS NULL)              AS expected,
               count(*) FILTER (WHERE p.status = 'UPLOADED' AND a.error IS NULL
                                  AND a.embedding IS NOT NULL)                                AS embedded,
               count(*) FILTER (WHERE p.status = 'UPLOADED' AND a.error IS NULL
                                  AND a.embedding IS NOT NULL AND a.clip_embedding IS NOT NULL) AS scored,
               count(*) FILTER (WHERE p.status = 'UPLOADED' AND a.error IS NULL
                                  AND a.embedding IS NOT NULL AND a.clip_embedding IS NOT NULL
                                  AND a.technical_pct IS NOT NULL)                            AS categorized
        FROM photos p
        LEFT JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.gallery_id = :galleryId AND p.deleted_at IS NULL
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .param("liveSince", Timestamp.from(liveSince.toInstant()))
        .query { rs, _ ->
            GalleryAnalysisProgress(
                total = rs.getLong("total"),
                pending = rs.getLong("pending"),
                livePending = rs.getLong("live_pending"),
                failed = rs.getLong("failed"),
                expected = rs.getLong("expected"),
                embedded = rs.getLong("embedded"),
                scored = rs.getLong("scored"),
                categorized = rs.getLong("categorized"),
            )
        }
        .single()

    /**
     * HeadObject로 확인할 PENDING 사진. 한 번도 안 본 행은 만들어진 지 [firstCheckAfter]가 지나야 보고,
     * 본 적 있는 행은 마지막으로 본 뒤([touchPending]이 `updated_at`을 옮긴다) [recheckBefore]가 지나야 다시 본다.
     * "본 적 없음"은 `updated_at`이 `created_at`에서 [firstCheckAfter] 안에 있는 것이다 — 첫 검사가 그보다 뒤에 오므로
     * 그 안의 갱신은 발급 트랜잭션(URL 만료 시각 기록)이나 재발급뿐이다.
     */
    fun findPendingToCheck(
        now: ZonedDateTime,
        firstCheckAfter: Duration,
        recheckBefore: ZonedDateTime,
        limit: Int,
    ): List<PendingPhotoDto> = jdbcClient.sql(
        """
        SELECT id, storage_key, created_at
        FROM photos
        WHERE status = 'PENDING' AND deleted_at IS NULL
          AND CASE WHEN updated_at < created_at + make_interval(secs => :firstCheckSeconds)
                   THEN created_at < :firstCheckBefore
                   ELSE updated_at < :recheckBefore
              END
        ORDER BY created_at
        LIMIT :limit
        """.trimIndent(),
    )
        .param("firstCheckSeconds", firstCheckAfter.seconds.toDouble())
        .param("firstCheckBefore", Timestamp.from(now.minus(firstCheckAfter).toInstant()))
        .param("recheckBefore", Timestamp.from(recheckBefore.toInstant()))
        .param("limit", limit)
        .query { rs, _ ->
            PendingPhotoDto(
                photoId = rs.getLong("id"),
                storageKey = rs.getString("storage_key"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toZonedDateTime(),
            )
        }
        .list()

    /** 객체가 있는 것으로 확인된 PENDING 사진을 UPLOADED로. 그 사이 프론트가 통보했으면 0행이고 그것으로 충분하다. */
    fun markUploaded(photoIds: Collection<Long>, now: ZonedDateTime): Int {
        if (photoIds.isEmpty()) return 0
        return jdbcClient.sql(
            """
            UPDATE photos
            SET status = 'UPLOADED', version = version + 1, updated_at = :now
            WHERE id IN (:photoIds) AND status = 'PENDING' AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("photoIds", photoIds.toList())
            .param("now", Timestamp.from(now.toInstant()))
            .update()
    }

    /** 확인했지만 아직 객체가 없는 PENDING 사진의 검사 시각을 남긴다. 다음 검사는 [findPendingToCheck]의 재검사 창 뒤다. */
    fun touchPending(photoIds: Collection<Long>, now: ZonedDateTime): Int {
        if (photoIds.isEmpty()) return 0
        return jdbcClient.sql(
            """
            UPDATE photos
            SET updated_at = :now
            WHERE id IN (:photoIds) AND status = 'PENDING' AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("photoIds", photoIds.toList())
            .param("now", Timestamp.from(now.toInstant()))
            .update()
    }

    /** 오래 기다려도 객체가 오지 않은 PENDING 사진을 휴지통으로. 복원·물리 삭제는 trash 도메인이 이어받는다. */
    fun moveToTrash(photoIds: Collection<Long>, now: ZonedDateTime): Int {
        if (photoIds.isEmpty()) return 0
        return jdbcClient.sql(
            """
            UPDATE photos
            SET deleted_at = :now, version = version + 1, updated_at = :now
            WHERE id IN (:photoIds) AND status = 'PENDING' AND deleted_at IS NULL
            """.trimIndent(),
        )
            .param("photoIds", photoIds.toList())
            .param("now", Timestamp.from(now.toInstant()))
            .update()
    }
}
