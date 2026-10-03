package com.soma.wes.photo.repository

import com.soma.wes.photo.dto.AnalysisFailureDto
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
 *
 * 네 묶음이 있다 — 진행 카운트([progressOf]), PENDING 보정([findPendingToCheck]·[markUploaded]·[touchPending]·[moveToTrash]),
 * 임베더 배정과 리셋([claimForEmbedding]·[releaseStaleDispatches]·[markEmbedAttemptsExceeded]·[resetAnalysis]·[resetFailedAnalysis]),
 * 잡의 대기 끝내기([markAnalysisStalled]·[isCategorizedAsOf]),
 * GPU 제어·score 폴백([countScoreBacklog]·[findGalleryIdsWithUnscoredPhotos]·[findUnscoredPhotoIds]).
 * 하트비트는 이 중 대기량([countPending]·[countInFlightEmbedBatches]·[countScoreBacklog])을 1분마다 읽는다.
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
            SET status = 'UPLOADED', uploaded_at = :now, version = version + 1, updated_at = :now
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

    /**
     * 임베더 배정이 남아 있는 갤러리 — 배정할 수 있는 사진([claimForEmbedding]과 같은 조건)이 한 장이라도 있는 갤러리를
     * 가장 오래 기다린 사진 순으로. 스윕이 이 순서로 돌며 갤러리마다 한 배치씩 집는다.
     */
    fun findGalleryIdsWithEmbedBacklog(maxAttempts: Int): List<Long> = jdbcClient.sql(
        """
        SELECT p.gallery_id
        FROM photos p
        LEFT JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.status = 'UPLOADED' AND p.deleted_at IS NULL AND p.dispatched_at IS NULL
          AND p.embed_attempts < :maxAttempts AND a.embedding IS NULL AND a.error IS NULL
        GROUP BY p.gallery_id
        ORDER BY min(p.id)
        """.trimIndent(),
    )
        .param("maxAttempts", maxAttempts)
        .query { rs, _ -> rs.getLong("gallery_id") }
        .list()

    /**
     * 갤러리 하나에서 임베더에 보낼 사진을 집는다 — 벡터·실패·배정이 없고 시도가 남은 UPLOADED 사진을 id 순으로 [limit]장.
     * `FOR UPDATE SKIP LOCKED`라 스윕 둘이 같은 갤러리를 봐도 겹치지 않고, 집는 것과 배정 표시가 한 문장이라 사이가 없다.
     * 배정 시각은 문장 시작 시각(`statement_timestamp()`)이다 — 한 배치의 사진은 같은 시각을 받고 배치끼리는 다르므로
     * 시각의 수가 곧 떠 있는 배치 수다([countInFlightEmbedBatches]). 돌려준 id 목록이 곧 EVENT 페이로드다.
     */
    fun claimForEmbedding(galleryId: Long, limit: Int, maxAttempts: Int): List<Long> = jdbcClient.sql(
        """
        UPDATE photos
        SET dispatched_at = statement_timestamp(), embed_attempts = embed_attempts + 1
        WHERE id IN (
            SELECT p.id
            FROM photos p
            LEFT JOIN photo_analysis a ON a.photo_id = p.id
            WHERE p.gallery_id = :galleryId AND p.status = 'UPLOADED' AND p.deleted_at IS NULL
              AND a.embedding IS NULL AND a.error IS NULL
              AND p.dispatched_at IS NULL AND p.embed_attempts < :maxAttempts
            ORDER BY p.id
            LIMIT :limit
            FOR UPDATE OF p SKIP LOCKED
        )
        RETURNING id
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .param("maxAttempts", maxAttempts)
        .param("limit", limit)
        .query { rs, _ -> rs.getLong("id") }
        .list()
        .sorted()

    /**
     * 호출 자체가 실패한 배치의 배정을 통째로 되돌린다. 시도 수도 돌려놓는다 — 권한·스로틀링은 사진의 잘못이 아니라
     * 상한을 갉아먹으면 안 된다. 다음 스윕이 같은 사진을 다시 집는다.
     */
    fun releaseClaim(photoIds: Collection<Long>): Int {
        if (photoIds.isEmpty()) return 0
        return jdbcClient.sql(
            """
            UPDATE photos
            SET dispatched_at = NULL, embed_attempts = greatest(embed_attempts - 1, 0)
            WHERE id IN (:photoIds) AND dispatched_at IS NOT NULL
            """.trimIndent(),
        )
            .param("photoIds", photoIds.toList())
            .update()
    }

    /**
     * 배정한 지 오래됐는데 아직 벡터가 없는 사진의 배정을 되돌린다(Lambda가 죽었거나 시간을 넘긴 배치).
     * 시도 수는 그대로다 — 상한은 이 경로로 찬다. 되돌린 사진은 다음 스윕이 다시 집는다.
     */
    fun releaseStaleDispatches(before: ZonedDateTime): Int = jdbcClient.sql(
        """
        UPDATE photos p
        SET dispatched_at = NULL
        WHERE p.status = 'UPLOADED' AND p.deleted_at IS NULL
          AND p.dispatched_at IS NOT NULL AND p.dispatched_at < :before
          AND NOT EXISTS (SELECT 1 FROM photo_analysis a WHERE a.photo_id = p.id AND (a.embedding IS NOT NULL OR a.error IS NOT NULL))
        """.trimIndent(),
    )
        .param("before", Timestamp.from(before.toInstant()))
        .update()

    /**
     * 시도 상한에 닿고도 벡터가 없는 사진을 결정적 실패로 표시한다 — `photo_analysis.error`를 UPSERT. 이후 배정·기대 장수·
     * 집기 전부에서 빠지고, 사진 상세·요약에 실패로 드러난다. 되돌리는 길은 재분석 리셋([resetAnalysis])뿐이다.
     *
     * 표시한 사진 id를 갤러리별로 돌려준다 — "어느 갤러리의 어느 사진을 포기했나"를 로그에 남기기 위해서다. 없으면 빈 맵이다.
     */
    fun markEmbedAttemptsExceeded(maxAttempts: Int, now: ZonedDateTime): Map<Long, List<Long>> = jdbcClient.sql(
        """
        WITH marked AS (
            INSERT INTO photo_analysis (photo_id, error, created_at, updated_at)
            SELECT p.id, :error, :now, :now
            FROM photos p
            LEFT JOIN photo_analysis a ON a.photo_id = p.id
            WHERE p.status = 'UPLOADED' AND p.deleted_at IS NULL
              AND p.dispatched_at IS NULL AND p.embed_attempts >= :maxAttempts
              AND a.embedding IS NULL AND a.error IS NULL
            ON CONFLICT (photo_id) DO UPDATE
            SET error = EXCLUDED.error, version = photo_analysis.version + 1, updated_at = EXCLUDED.updated_at
            RETURNING photo_id
        )
        SELECT p.gallery_id, p.id
        FROM marked m
        JOIN photos p ON p.id = m.photo_id
        ORDER BY p.gallery_id, p.id
        """.trimIndent(),
    )
        .param("error", EMBED_ATTEMPTS_EXCEEDED)
        .param("maxAttempts", maxAttempts)
        .param("now", Timestamp.from(now.toInstant()))
        .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }
        .list()
        .groupBy({ (galleryId, _) -> galleryId }, { (_, photoId) -> photoId })

    /**
     * 진행이 멈춘 갤러리에서 뒤처진 사진(점수가 없는 대상 — 분석 행이 없거나 CLIP 벡터가 없다)을 결정적 실패로 표시한다.
     * 이후 기대 장수에서 빠져 잡이 나머지로 계속 간다. 표시한 사진 id를 돌려준다. 되돌리는 길은 재분석 리셋([resetAnalysis])뿐이다.
     */
    fun markAnalysisStalled(galleryId: Long, now: ZonedDateTime): List<Long> = jdbcClient.sql(
        """
        INSERT INTO photo_analysis (photo_id, error, created_at, updated_at)
        SELECT p.id, :error, :now, :now
        FROM photos p
        LEFT JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.gallery_id = :galleryId AND p.status = 'UPLOADED' AND p.deleted_at IS NULL
          AND a.clip_embedding IS NULL AND a.error IS NULL
        ON CONFLICT (photo_id) DO UPDATE
        SET error = EXCLUDED.error, version = photo_analysis.version + 1, updated_at = EXCLUDED.updated_at
        RETURNING photo_id
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .param("error", ANALYSIS_STALLED)
        .param("now", Timestamp.from(now.toInstant()))
        .query { rs, _ -> rs.getLong(1) }
        .list()

    /**
     * categorize 를 보낸 시각([dispatchedAt])까지 올라온 대상 사진 전부에 백분위가 있는가. 보낸 뒤에 올라온 사진은 그 categorize 가
     * 보지 못했을 수 있어 기다리지 않는다 — 기다리면 분류 중에 사진을 더 올린 잡이 영영 "덜 끝난" 상태로 남는다.
     * 올라온 시각이 비어 있는 사진은 보낸 시각 이전에 올라온 것으로 친다(기다리는 쪽이 안전하다).
     */
    fun isCategorizedAsOf(galleryId: Long, dispatchedAt: ZonedDateTime): Boolean = jdbcClient.sql(
        """
        SELECT NOT EXISTS (
            SELECT 1
            FROM photos p
            LEFT JOIN photo_analysis a ON a.photo_id = p.id
            WHERE p.gallery_id = :galleryId AND p.status = 'UPLOADED' AND p.deleted_at IS NULL
              AND (p.uploaded_at IS NULL OR p.uploaded_at <= :dispatchedAt)
              AND a.error IS NULL AND a.technical_pct IS NULL
        )
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .param("dispatchedAt", Timestamp.from(dispatchedAt.toInstant()))
        .query { rs, _ -> rs.getBoolean(1) }
        .single()

    /**
     * 갤러리에서 [since] 이후에 실패로 표시된 사진 수를 사유(`photo_analysis.error`)별로 센다. 전부 세려면 아주 이른 시각을 넘긴다.
     * 실패를 쓰는 세 주체(이 서버·임베더·score)가 모두 `updated_at`을 그 시각으로 쓴다. 실패한 행은 이후 어느 단계도 다시 쓰지 않으므로
     * `updated_at`이 곧 실패한 시각이다.
     */
    fun countAnalysisFailuresByError(galleryId: Long, since: ZonedDateTime): Map<String, Long> = jdbcClient.sql(
        """
        SELECT a.error, count(*) AS photos
        FROM photos p
        JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.gallery_id = :galleryId AND p.status = 'UPLOADED' AND p.deleted_at IS NULL
          AND a.error IS NOT NULL AND a.updated_at > :since
        GROUP BY a.error
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .param("since", Timestamp.from(since.toInstant()))
        .query { rs, _ -> rs.getString("error") to rs.getLong("photos") }
        .list()
        .toMap()

    /** 완료 통보도 서버 확인도 아직 없는 PENDING 사진 전체 수. 하트비트가 "올라오다 만 사진이 쌓이고 있나"를 보는 값이다. */
    fun countPending(): Long = jdbcClient.sql(
        "SELECT count(*) FROM photos p WHERE p.status = 'PENDING' AND p.deleted_at IS NULL",
    )
        .query { rs, _ -> rs.getLong(1) }
        .single()

    /** 지금 떠 있는 임베더 배치 수. 한 배치의 사진은 같은 `dispatched_at`을 받으므로 서로 다른 시각의 수가 곧 배치 수다. */
    fun countInFlightEmbedBatches(): Int = jdbcClient.sql(
        """
        SELECT count(DISTINCT p.dispatched_at)
        FROM photos p
        LEFT JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.status = 'UPLOADED' AND p.deleted_at IS NULL AND p.dispatched_at IS NOT NULL
          AND a.embedding IS NULL AND a.error IS NULL
        """.trimIndent(),
    )
        .query { rs, _ -> rs.getInt(1) }
        .single()

    /**
     * GPU 워커가 곧 집게 될 일의 양 — 업로드됐고 실패하지 않았는데 점수가 없는 사진 전부(아직 벡터도 없는 사진 포함).
     * 켤지 말지의 기준이다: 벡터가 오기 전에 미리 켜 두면 부팅 시간이 임베딩과 겹친다.
     */
    fun countScoreBacklog(): Long = jdbcClient.sql(
        """
        SELECT count(*)
        FROM photos p
        LEFT JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.status = 'UPLOADED' AND p.deleted_at IS NULL
          AND a.clip_embedding IS NULL AND a.error IS NULL
        """.trimIndent(),
    )
        .query { rs, _ -> rs.getLong(1) }
        .single()

    /** score 폴백을 보낼 갤러리 — 벡터는 있는데 점수가 없는 사진이 있는 갤러리를 오래 기다린 순으로. */
    fun findGalleryIdsWithUnscoredPhotos(): List<Long> = jdbcClient.sql(
        """
        SELECT p.gallery_id
        FROM photos p
        JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.status = 'UPLOADED' AND p.deleted_at IS NULL
          AND a.embedding IS NOT NULL AND a.clip_embedding IS NULL AND a.error IS NULL
        GROUP BY p.gallery_id
        ORDER BY min(p.id)
        """.trimIndent(),
    )
        .query { rs, _ -> rs.getLong("gallery_id") }
        .list()

    /** score 폴백 대상 — 벡터는 있는데 점수(CLIP)가 없고 실패하지 않은 사진. 배정 컬럼 없이 갤러리 전체를 매번 다시 본다. */
    fun findUnscoredPhotoIds(galleryId: Long): List<Long> = jdbcClient.sql(
        """
        SELECT p.id
        FROM photos p
        JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.gallery_id = :galleryId AND p.status = 'UPLOADED' AND p.deleted_at IS NULL
          AND a.embedding IS NOT NULL AND a.clip_embedding IS NULL AND a.error IS NULL
        ORDER BY p.id
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .query { rs, _ -> rs.getLong("id") }
        .list()

    /**
     * 재분석·재처리 = 데이터 리셋. 갤러리의 분석 행을 지우고 배정 추적을 초기화한다 — 다음 스윕이 전부 다시 배정한다.
     * 분석 행을 참조하는 FK는 없고 폴더·추천은 `photo_id`로 사진을 가리키므로 안전하다. 휴지통 사진의 행은 남긴다(복원 대비).
     */
    fun resetAnalysis(galleryId: Long): Int {
        val deleted = jdbcClient.sql(
            """
            DELETE FROM photo_analysis
            WHERE photo_id IN (SELECT id FROM photos WHERE gallery_id = :galleryId AND deleted_at IS NULL)
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .update()
        jdbcClient.sql("UPDATE photos SET dispatched_at = NULL, embed_attempts = 0 WHERE gallery_id = :galleryId")
            .param("galleryId", galleryId)
            .update()
        return deleted
    }

    /**
     * [resetAnalysis]의 좁은 판 — 결정적으로 실패한 사진(`photo_analysis.error`)의 분석 행만 지우고 그 사진의 배정 추적만 초기화한다.
     * 정상 사진의 벡터·점수는 그대로라 다음 스윕이 다시 배정하는 것은 실패한 장뿐이다. 되돌린 사진 id를 돌려준다.
     * 다시 실패하면 같은 상한([markEmbedAttemptsExceeded])에 걸려 다시 `error`로 남는다 — 스스로 반복하지 않는다.
     */
    fun resetFailedAnalysis(galleryId: Long): List<Long> {
        val photoIds = jdbcClient.sql(
            """
            DELETE FROM photo_analysis a
            USING photos p
            WHERE p.id = a.photo_id AND p.gallery_id = :galleryId AND p.status = 'UPLOADED' AND p.deleted_at IS NULL
              AND a.error IS NOT NULL
            RETURNING a.photo_id
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> rs.getLong(1) }
            .list()
            .sorted()
        if (photoIds.isEmpty()) return photoIds
        jdbcClient.sql("UPDATE photos SET dispatched_at = NULL, embed_attempts = 0 WHERE id IN (:photoIds)")
            .param("photoIds", photoIds)
            .update()
        return photoIds
    }

    /** 갤러리에서 분석이 결정적으로 실패한 사진과 그 사유. 실패로 표시된 시각(분석 행의 `updated_at`) 순이다. */
    fun findAnalysisFailures(galleryId: Long): List<AnalysisFailureDto> = jdbcClient.sql(
        """
        SELECT p.id, p.original_file_name, a.error, a.updated_at
        FROM photos p
        JOIN photo_analysis a ON a.photo_id = p.id
        WHERE p.gallery_id = :galleryId AND p.status = 'UPLOADED' AND p.deleted_at IS NULL
          AND a.error IS NOT NULL
        ORDER BY a.updated_at, p.id
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .query { rs, _ ->
            AnalysisFailureDto(
                photoId = rs.getLong("id"),
                originalFileName = rs.getString("original_file_name"),
                error = rs.getString("error"),
                failedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toZonedDateTime(),
            )
        }
        .list()

    companion object {
        /** 이 서버가 쓰는 `photo_analysis.error` 값 — 임베더 배정 상한. 임베더·score의 값(디코드 실패 등)과 같은 컬럼을 나눠 쓴다. */
        const val EMBED_ATTEMPTS_EXCEEDED = "EMBED_ATTEMPTS_EXCEEDED"

        /** 이 서버가 쓰는 `photo_analysis.error` 값 — 진행이 멈춘 잡에서 떼어 낸 사진. */
        const val ANALYSIS_STALLED = "ANALYSIS_STALLED"
    }
}
