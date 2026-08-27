package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.support.AdminAlbumTemplateLayout
import com.soma.wes.admin.resource.support.InvalidAlbumTemplateLayoutException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/** DB가 비동기 작업의 단일 진실 원천이 되도록 claim과 상태 전이를 원자적으로 수행한다. */
@Repository
class AdminWorkflowExecutionRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun claimProcessingJobs(
        limit: Int,
        maxAttempts: Int,
        retryDelay: Duration,
        staleTimeout: Duration,
    ): List<ProcessingExecutionJob> = jdbcClient.sql(
        """
        WITH picked AS (
            SELECT id
            FROM admin_processing_jobs
            WHERE (
                attempt_count < :maxAttempts
                AND (
                    status = 'PENDING'
                    OR (status = 'FAILED' AND COALESCE(last_run_at, created_at) <=
                        CURRENT_TIMESTAMP - make_interval(secs => :retrySeconds))
                )
              )
              OR (
                status = 'DISPATCHING' AND failure_code = :claimedNotSent
                AND last_run_at <= CURRENT_TIMESTAMP - make_interval(secs => :staleSeconds)
              )
              OR (
                status = 'DISPATCHING' AND job_type = 'MOCK_RECALCULATION'
                AND failure_code IS NULL
                AND last_run_at <= CURRENT_TIMESTAMP - make_interval(secs => :staleSeconds)
              )
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
        )
        UPDATE admin_processing_jobs j
        SET status = 'DISPATCHING', failure_code = :claimedNotSent,
            last_run_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        FROM picked p
        WHERE j.id = p.id
        RETURNING j.id, j.job_type, j.target_type, j.target_id, j.revision_id,
                  j.payload::TEXT, j.attempt_count, j.actor_admin_id, j.reason
        """.trimIndent(),
    )
        .param("maxAttempts", maxAttempts.coerceAtLeast(1))
        .param("retrySeconds", retryDelay.seconds.coerceAtLeast(0))
        .param("staleSeconds", staleTimeout.seconds.coerceAtLeast(1))
        .param("claimedNotSent", CLAIMED_NOT_SENT)
        .param("limit", limit.coerceIn(1, 100))
        .query { rs, _ -> ProcessingExecutionJob(
            id = rs.getLong("id"),
            jobType = rs.getString("job_type"),
            targetType = AdminResourceType.valueOf(rs.getString("target_type")),
            targetId = rs.getLong("target_id"),
            revisionId = rs.getLong("revision_id").takeUnless { rs.wasNull() },
            payload = jsonMap(rs.getString("payload")),
            attemptCount = rs.getInt("attempt_count"),
            actorAdminId = rs.getObject("actor_admin_id", java.lang.Long::class.java)?.toLong(),
            reason = rs.getString("reason"),
        ) }
        .list()

    /**
     * Lambda 비동기 이벤트 수명과 함수 제한을 모두 넘긴 exact-photo 작업을 실패로 회수한다.
     *
     * `DISPATCHING/failure_code IS NULL`은 attempt를 소비한 직후 프로세스가 중단됐거나,
     * 외부 호출은 끝났지만 DISPATCHED finalize 전에 중단된 상태다. 둘을 즉시 구분할 수는
     * 없으므로 이벤트가 더는 도착하거나 실행될 수 없는 [dispatchedTimeout] 뒤에만 FAILED로
     * 바꾼다. 다음 retry는 attempt를 증가시키며, 늦은 이전 이벤트는 worker의 attempt CAS에서
     * 탈락한다. 명시적인 결과 불명 상태도 같은 안전 시간이 지난 뒤 회수한다.
     *
     * 상태 변경 뒤 감사가 실패하면 호출 측 트랜잭션이 이 UPDATE도 함께 rollback한다.
     */
    @Transactional
    fun markStaleExactPhotoJobsFailed(
        limit: Int,
        dispatchedTimeout: Duration,
    ): List<ProcessingExecutionJob> = jdbcClient.sql(
        """
        WITH picked AS (
            SELECT id
            FROM admin_processing_jobs
            WHERE job_type IN ('DERIVATIVE', 'EMBEDDING', 'QUALITY_ANALYSIS')
              AND (
                status = 'DISPATCHED'
                OR (
                    status = 'DISPATCHING'
                    AND (failure_code IS NULL OR failure_code = :outcomeUnknown)
                )
              )
              AND COALESCE(last_run_at, updated_at, created_at) <=
                  CURRENT_TIMESTAMP - make_interval(secs => :timeoutSeconds)
            ORDER BY COALESCE(last_run_at, updated_at, created_at), id
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
        )
        UPDATE admin_processing_jobs j
        SET status = 'FAILED', failure_code = :failureCode,
            last_run_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        FROM picked p
        WHERE j.id = p.id
          AND (
            j.status = 'DISPATCHED'
            OR (
                j.status = 'DISPATCHING'
                AND (j.failure_code IS NULL OR j.failure_code = :outcomeUnknown)
            )
          )
        RETURNING j.id, j.job_type, j.target_type, j.target_id, j.revision_id,
                  j.payload::TEXT, j.attempt_count, j.actor_admin_id, j.reason
        """.trimIndent(),
    )
        .param("timeoutSeconds", dispatchedTimeout.seconds.coerceAtLeast(1))
        .param("failureCode", DISPATCH_RESULT_TIMEOUT)
        .param("outcomeUnknown", DISPATCH_OUTCOME_UNKNOWN)
        .param("limit", limit.coerceIn(1, 100))
        .query { rs, _ -> ProcessingExecutionJob(
            id = rs.getLong("id"),
            jobType = rs.getString("job_type"),
            targetType = AdminResourceType.valueOf(rs.getString("target_type")),
            targetId = rs.getLong("target_id"),
            revisionId = rs.getLong("revision_id").takeUnless { rs.wasNull() },
            payload = jsonMap(rs.getString("payload")),
            attemptCount = rs.getInt("attempt_count"),
            actorAdminId = rs.getObject("actor_admin_id", java.lang.Long::class.java)?.toLong(),
            reason = rs.getString("reason"),
        ) }
        .list()

    fun targetActive(type: AdminResourceType, id: Long): Boolean {
        val table = targetTable(type)
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM $table WHERE id = :id AND deleted_at IS NULL)")
            .param("id", id)
            .query { rs, _ -> rs.getBoolean(1) }
            .single()
    }

    /** 실제 실행 직전 대상 생존과 lease를 CAS하고, 이 시점에만 attempt를 소비한다. */
    fun markProcessingExecutionStarted(
        jobId: Long,
        attemptCount: Int,
        targetType: AdminResourceType,
        targetId: Long,
    ): Int? {
        val table = targetTable(targetType)
        return jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET attempt_count = attempt_count + 1, failure_code = NULL,
                last_run_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :jobId AND status = 'DISPATCHING'
              AND attempt_count = :attemptCount AND failure_code = :claimedNotSent
              AND EXISTS(
                  SELECT 1 FROM $table
                  WHERE id = :targetId AND deleted_at IS NULL
              )
            RETURNING attempt_count
            """.trimIndent(),
        )
            .param("jobId", jobId)
            .param("attemptCount", attemptCount)
            .param("targetId", targetId)
            .param("claimedNotSent", CLAIMED_NOT_SENT)
            .query { rs, _ -> rs.getInt("attempt_count") }
            .optional()
            .orElse(null)
    }

    /** 명확한 설정/검증 실패는 FAILED 전이와 실제 실행 attempt 소비를 한 CAS로 묶는다. */
    fun markProcessingJobFailedBeforeStart(
        id: Long,
        attemptCount: Int,
        failureCode: String,
    ): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET status = 'FAILED', attempt_count = attempt_count + 1,
            failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code = :claimedNotSent
        """.trimIndent(),
    )
        .param("failureCode", failureCode.take(80))
        .param("id", id)
        .param("attemptCount", attemptCount)
        .param("claimedNotSent", CLAIMED_NOT_SENT)
        .update()

    /** 마지막 실제 attempt 뒤 복구된 로컬 lease는 재실행하지 않고 실패로 종결한다. */
    fun markProcessingJobAttemptsExhausted(
        id: Long,
        attemptCount: Int,
    ): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET status = 'FAILED', failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code = :claimedNotSent
        """.trimIndent(),
    )
        .param("failureCode", MAX_ATTEMPTS_EXHAUSTED)
        .param("id", id)
        .param("attemptCount", attemptCount)
        .param("claimedNotSent", CLAIMED_NOT_SENT)
        .update()

    /** 로컬 실행 시작 뒤에는 현재 attempt와 send-start 상태가 모두 일치해야만 terminal 전이한다. */
    fun markStartedProcessingJob(
        id: Long,
        attemptCount: Int,
        status: String,
        failureCode: String? = null,
    ): Int {
        require(status in LOCAL_PROCESSING_TERMINAL_STATUSES)
        return jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET status = :status, failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
              AND failure_code IS NULL
            """.trimIndent(),
        )
            .param("status", status)
            .param("failureCode", failureCode?.take(80))
            .param("id", id)
            .param("attemptCount", attemptCount)
            .update()
    }

    /** lease가 그대로이고 대상이 실제 삭제된 경우에만 미실행 claim을 취소한다. */
    fun cancelClaimedProcessingJobIfTargetDeleted(
        id: Long,
        attemptCount: Int,
        targetType: AdminResourceType,
        targetId: Long,
    ): Int {
        val table = targetTable(targetType)
        return jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET status = 'CANCELED', failure_code = 'TARGET_DELETED', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
              AND failure_code = :claimedNotSent
              AND NOT EXISTS(
                  SELECT 1 FROM $table
                  WHERE id = :targetId AND deleted_at IS NULL
              )
            """.trimIndent(),
        )
            .param("id", id)
            .param("attemptCount", attemptCount)
            .param("targetId", targetId)
            .param("claimedNotSent", CLAIMED_NOT_SENT)
            .update()
    }

    /** 외부 호출 정상 반환 뒤 send-start claim이 그대로일 때만 DISPATCHED로 확정한다. */
    fun markProcessingJobDispatched(id: Long, attemptCount: Int): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET status = 'DISPATCHED', failure_code = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code IS NULL
        """.trimIndent(),
    )
        .param("id", id)
        .param("attemptCount", attemptCount)
        .update()

    /** 외부 전송 결과를 확정할 수 없으면 DISPATCHING을 유지하되 자동 claim 대상에서 제외한다. */
    fun markProcessingJobAmbiguous(
        id: Long,
        attemptCount: Int,
        failureCode: String,
    ): Int = jdbcClient.sql(
        """
        UPDATE admin_processing_jobs
        SET failure_code = :failureCode, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'DISPATCHING' AND attempt_count = :attemptCount
          AND failure_code IS NULL
        """.trimIndent(),
    )
        .param("failureCode", failureCode.take(80))
        .param("id", id)
        .param("attemptCount", attemptCount)
        .update()

    /** 제출 리비전의 사진 순서를 현재 템플릿 목업에 한 트랜잭션으로 반영한다. */
    @Transactional
    fun rebuildMock(job: ProcessingExecutionJob): MockRecalculationResult {
        val revisionId = job.revisionId ?: throw WorkflowExecutionException("REVISION_ID_REQUIRED")
        val revision = jdbcClient.sql(
            """
            SELECT s.gallery_id, r.photo_items::TEXT
            FROM admin_selection_revisions r
            JOIN photo_selections s ON s.id = r.selection_id
            WHERE r.id = :revisionId AND r.selection_id = :selectionId
              AND r.status = 'SUBMITTED' AND s.deleted_at IS NULL
            FOR UPDATE OF s
            """.trimIndent(),
        )
            .param("revisionId", revisionId)
            .param("selectionId", job.targetId)
            .query { rs, _ -> SubmittedRevision(
                galleryId = rs.getLong("gallery_id"),
                items = selectionItems(rs.getString("photo_items")),
            ) }
            .optional()
            .orElseThrow { WorkflowExecutionException("INVALID_SUBMITTED_REVISION") }
        if (revision.items.isEmpty() || revision.items.map { it.photoId }.distinct().size != revision.items.size) {
            throw WorkflowExecutionException("INVALID_REVISION_ITEMS")
        }
        val activePhotoCount = jdbcClient.sql(
            """
            SELECT COUNT(*) FROM photos
            WHERE gallery_id = :galleryId AND id IN (:photoIds)
              AND deleted_at IS NULL AND status <> 'PENDING'
            """.trimIndent(),
        )
            .param("galleryId", revision.galleryId)
            .param("photoIds", revision.items.map { it.photoId })
            .query { rs, _ -> rs.getLong(1) }
            .single()
        if (activePhotoCount != revision.items.size.toLong()) {
            throw WorkflowExecutionException("REVISION_PHOTO_NOT_ACTIVE")
        }
        val albums = jdbcClient.sql(
            """
            SELECT album.id, template.layout_json::TEXT
            FROM photo_folder_groups album
            JOIN admin_album_templates template ON template.id = album.template_id
            WHERE album.gallery_id = :galleryId AND album.deleted_at IS NULL
              AND template.deleted_at IS NULL
            ORDER BY album.id
            FOR UPDATE OF album, template
            """.trimIndent(),
        )
            .param("galleryId", revision.galleryId)
            .query { rs, _ -> TemplateAlbum(
                id = rs.getLong("id"),
                layout = templateLayout(rs.getString("layout_json")),
            ) }
            .list()
        if (albums.isEmpty()) throw WorkflowExecutionException("NO_TEMPLATE_ALBUM")

        val revisionItems = revision.items.withIndex()
            .sortedWith(compareBy<IndexedValue<SelectionRevisionItem>> { it.value.sortOrder }.thenBy { it.index })
            .map(IndexedValue<SelectionRevisionItem>::value)
        albums.forEach { album ->
            val existingRows = existingAlbumRows(album.id)
            val plan = buildAlbumPlan(existingRows, album.layout, revisionItems)
            jdbcClient.sql("DELETE FROM photo_folders WHERE group_id = :albumId")
                .param("albumId", album.id)
                .update()
            plan.forEach { folder ->
                val folderId = jdbcClient.sql(
                    """
                    INSERT INTO photo_folders (group_id, gallery_id, name, version, created_at, updated_at)
                    VALUES (:albumId, :galleryId, :name, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    RETURNING id
                    """.trimIndent(),
                )
                    .param("albumId", album.id)
                    .param("galleryId", revision.galleryId)
                    .param("name", folder.name)
                    .query { rs, _ -> rs.getLong(1) }
                    .single()
                folder.items.forEachIndexed { index, item ->
                    jdbcClient.sql(
                        """
                        INSERT INTO photo_folder_items
                            (group_id, folder_id, photo_id, sort_order, crop_json,
                             version, created_at, updated_at)
                        VALUES
                            (:albumId, :folderId, :photoId, :sortOrder, CAST(:cropJson AS JSONB),
                             0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    )
                        .param("albumId", album.id)
                        .param("folderId", folderId)
                        .param("photoId", item.photoId)
                        .param("sortOrder", index)
                        .param("cropJson", item.cropJson)
                        .update()
                }
            }
            jdbcClient.sql(
                """
                UPDATE photo_folder_groups
                SET selection_revision_id = :revisionId, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :albumId AND deleted_at IS NULL
                """.trimIndent(),
            )
                .param("revisionId", revisionId)
                .param("albumId", album.id)
                .update()
        }
        if (markStartedProcessingJob(job.id, job.attemptCount, "SUCCEEDED") != 1) {
            throw WorkflowExecutionException("JOB_STATE_CONFLICT")
        }
        return MockRecalculationResult(albums.size, revision.items.size)
    }

    private fun existingAlbumRows(albumId: Long): List<ExistingAlbumRow> = jdbcClient.sql(
        """
        SELECT folder.id AS folder_id, folder.name, item.id AS item_id,
               item.photo_id, item.sort_order, item.crop_json::TEXT
        FROM photo_folders folder
        LEFT JOIN photo_folder_items item ON item.folder_id = folder.id
        WHERE folder.group_id = :albumId
        ORDER BY folder.id, item.sort_order, item.id
        """.trimIndent(),
    )
        .param("albumId", albumId)
        .query { rs, _ -> ExistingAlbumRow(
            folderId = rs.getLong("folder_id"),
            folderName = rs.getString("name"),
            itemId = rs.getObject("item_id", java.lang.Long::class.java)?.toLong(),
            photoId = rs.getObject("photo_id", java.lang.Long::class.java)?.toLong(),
            sortOrder = rs.getObject("sort_order", Integer::class.java)?.toInt(),
            cropJson = rs.getString("crop_json"),
        ) }
        .list()

    /**
     * 기존 수동 배치는 살아 있는 선택 사진에 대해 우선 보존한다. 템플릿 슬롯은 기존 배치가
     * 차지하지 않은 자리부터 새 사진에만 적용한다. 따라서 재실행해도 같은 입력은 같은
     * 폴더·상대 순서·crop을 만들고, 템플릿이 없는 legacy `{}`도 기존 목업을 잃지 않는다.
     */
    private fun buildAlbumPlan(
        existingRows: List<ExistingAlbumRow>,
        template: AdminAlbumTemplateLayout.Layout,
        revisionItems: List<SelectionRevisionItem>,
    ): List<PlannedFolder> {
        val selectedIds = revisionItems.map(SelectionRevisionItem::photoId).toSet()
        val folders = linkedMapOf<Long, PlannedFolder>()
        existingRows.forEach { row ->
            val folder = folders.getOrPut(row.folderId) { PlannedFolder(row.folderName) }
            if (row.photoId != null && row.itemId != null && row.photoId in selectedIds) {
                folder.items += PlannedItem(
                    photoId = row.photoId,
                    cropJson = row.cropJson,
                    previousItemId = row.itemId,
                    previousSortOrder = row.sortOrder ?: Int.MAX_VALUE,
                )
            }
        }
        folders.values.forEach { folder ->
            folder.items.sortWith(compareBy<PlannedItem> { it.previousSortOrder }.thenBy { it.previousItemId })
        }

        val foldersByName = folders.values.associateByTo(linkedMapOf(), PlannedFolder::name)
        template.folders.forEach { templateFolder ->
            foldersByName.getOrPut(templateFolder.name) {
                PlannedFolder(templateFolder.name).also { folders[-(folders.size + 1L)] = it }
            }
        }
        if (folders.isEmpty()) {
            val fallback = PlannedFolder("선택본")
            folders[-1L] = fallback
            foldersByName[fallback.name] = fallback
        }

        val occupiedPhotoIds = folders.values.flatMap { folder -> folder.items.map(PlannedItem::photoId) }.toSet()
        val availableSlots = mutableListOf<TemplateSlot>()
        template.folders.forEach { templateFolder ->
            val folder = checkNotNull(foldersByName[templateFolder.name])
            templateFolder.slots.forEachIndexed { index, slot ->
                val existing = folder.items.getOrNull(index)
                if (existing == null) {
                    availableSlots += TemplateSlot(folder, slot.crop?.let(objectMapper::writeValueAsString))
                }
            }
        }

        val overflowFolder = template.folders.lastOrNull()?.let { foldersByName[it.name] }
            ?: folders.values.first()
        val newPhotoIds = revisionItems.map(SelectionRevisionItem::photoId).filterNot(occupiedPhotoIds::contains)
        newPhotoIds.forEachIndexed { index, photoId ->
            val slot = availableSlots.getOrNull(index)
            val target = slot?.folder ?: overflowFolder
            target.items += PlannedItem(
                photoId = photoId,
                cropJson = slot?.cropJson,
                previousItemId = Long.MAX_VALUE,
                previousSortOrder = Int.MAX_VALUE,
            )
        }
        return folders.values.toList()
    }

    private fun templateLayout(value: String): AdminAlbumTemplateLayout.Layout = try {
        AdminAlbumTemplateLayout.parse(jsonMap(value))
    } catch (_: InvalidAlbumTemplateLayoutException) {
        throw WorkflowExecutionException("INVALID_TEMPLATE_LAYOUT")
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(value: String): Map<String, Any?> =
        objectMapper.readValue(value, Map::class.java).entries
            .associate { it.key.toString() to it.value }

    @Suppress("UNCHECKED_CAST")
    private fun selectionItems(value: String): List<SelectionRevisionItem> =
        objectMapper.readValue(value, List::class.java).mapIndexed { index, raw ->
            val item = raw as? Map<*, *> ?: throw WorkflowExecutionException("INVALID_REVISION_ITEMS")
            val photoId = (item["photoId"] as? Number)?.toLong()
                ?: item["photoId"]?.toString()?.toLongOrNull()
                ?: throw WorkflowExecutionException("INVALID_REVISION_ITEMS")
            val sortOrder = (item["sortOrder"] as? Number)?.toInt()
                ?: item["sortOrder"]?.toString()?.toIntOrNull()
                ?: index
            SelectionRevisionItem(photoId, sortOrder)
        }

    data class ProcessingExecutionJob(
        val id: Long,
        val jobType: String,
        val targetType: AdminResourceType,
        val targetId: Long,
        val revisionId: Long?,
        val payload: Map<String, Any?>,
        val attemptCount: Int,
        val actorAdminId: Long?,
        val reason: String,
    )

    data class MockRecalculationResult(val albumCount: Int, val photoCount: Int)
    private data class SubmittedRevision(val galleryId: Long, val items: List<SelectionRevisionItem>)
    private data class SelectionRevisionItem(val photoId: Long, val sortOrder: Int)
    private data class TemplateAlbum(val id: Long, val layout: AdminAlbumTemplateLayout.Layout)
    private data class ExistingAlbumRow(
        val folderId: Long,
        val folderName: String,
        val itemId: Long?,
        val photoId: Long?,
        val sortOrder: Int?,
        val cropJson: String?,
    )
    private data class PlannedFolder(
        val name: String,
        val items: MutableList<PlannedItem> = mutableListOf(),
    )
    private data class PlannedItem(
        val photoId: Long,
        val cropJson: String?,
        val previousItemId: Long,
        val previousSortOrder: Int,
    )
    private data class TemplateSlot(val folder: PlannedFolder, val cropJson: String?)

    companion object {
        const val CLAIMED_NOT_SENT = "CLAIMED_NOT_SENT"
        const val DISPATCH_OUTCOME_UNKNOWN = "DISPATCH_OUTCOME_UNKNOWN"
        const val MAX_ATTEMPTS_EXHAUSTED = "MAX_ATTEMPTS_EXHAUSTED"
        const val DISPATCH_RESULT_TIMEOUT = "DISPATCH_RESULT_TIMEOUT"
        private val LOCAL_PROCESSING_TERMINAL_STATUSES = setOf("SUCCEEDED", "FAILED")
    }

    private fun targetTable(type: AdminResourceType): String = when (type) {
        AdminResourceType.USER -> "users"
        AdminResourceType.STUDIO -> "studios"
        AdminResourceType.GALLERY -> "galleries"
        AdminResourceType.PHOTO -> "photos"
        AdminResourceType.SELECTION -> "photo_selections"
        AdminResourceType.COLLABORATION -> "collab_sessions"
        AdminResourceType.ALBUM -> "photo_folder_groups"
        AdminResourceType.RETOUCH_REQUEST -> "retouch_rounds"
    }
}

class WorkflowExecutionException(val failureCode: String) : RuntimeException(failureCode)
