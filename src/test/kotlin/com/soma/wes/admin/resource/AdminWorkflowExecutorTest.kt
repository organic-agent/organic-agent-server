package com.soma.wes.admin.resource

import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.resource.config.AdminWorkflowExecutorProperties
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.repository.AdminWorkflowExecutionRepository
import com.soma.wes.admin.resource.repository.AdminWorkflowRepository
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.resource.service.AdminWorkflowExecutor
import com.soma.wes.embedding.service.EmbeddingInvoker
import com.soma.wes.embedding.service.ExactPhotoProcessingRequest
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@IntegrationTest
class AdminWorkflowExecutorTest @Autowired constructor(
    private val executor: AdminWorkflowExecutor,
    private val workflowExecutorProperties: AdminWorkflowExecutorProperties,
    private val resourceService: AdminResourceService,
    private val adminAccountFixture: AdminAccountFixture,
    private val workflowExecutionRepository: AdminWorkflowExecutionRepository,
    private val workflowRepository: AdminWorkflowRepository,
    private val auditService: AdminAuditService,
    private val transactionTemplate: TransactionTemplate,
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    @MockitoBean
    private lateinit var embeddingInvoker: EmbeddingInvoker

    @BeforeEach
    fun setUp() {
        whenever(embeddingInvoker.isAvailable).thenReturn(true)
    }

    @Test
    fun `외부 결과 회수 기본값은 Lambda 대기 실행 여유를 합친 40분이다`() {
        assertThat(AdminWorkflowExecutorProperties().dispatchedTimeout).isEqualTo(Duration.ofMinutes(40))
        assertThat(workflowExecutorProperties.dispatchedTimeout).isEqualTo(Duration.ofMinutes(40))
    }

    @Test
    fun `claim은 처리 작업만 실행하고 폐기된 외부 알림 outbox는 건드리지 않는다`() {
        val graph = graph("executor-claim", photoCount = 2)
        val embeddingJob = processingJob("EMBEDDING", AdminResourceType.PHOTO, graph.photos[0].id)
        val derivativeJob = processingJob("DERIVATIVE", AdminResourceType.PHOTO, graph.photos[1].id)
        notification(AdminResourceType.GALLERY, graph.gallery.id)

        executor.runOnce()

        assertThat(processingState(embeddingJob)).isEqualTo(State("DISPATCHED", 1, null))
        assertThat(processingState(derivativeJob)).isEqualTo(State("DISPATCHED", 1, null))
        assertThat(notificationState()).isEqualTo(State("PENDING", 0, null))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, graph.photos[0].id))
            .containsExactly("REPROCESS_DISPATCHED:SUCCESS")
        assertThat(dispatchAudits(AdminResourceType.PHOTO, graph.photos[1].id))
            .containsExactly("REPROCESS_DISPATCHED:SUCCESS")
        val requests = argumentCaptor<ExactPhotoProcessingRequest>()
        verify(embeddingInvoker, times(2)).invoke(requests.capture())
        assertThat(requests.allValues.map { it.jobType })
            .containsExactlyInAnyOrder("EMBEDDING", "DERIVATIVE")
        assertThat(requests.allValues).allSatisfy { request ->
            assertThat(request.galleryId).isEqualTo(graph.gallery.id)
            assertThat(request.storageKey).startsWith("galleries/${graph.gallery.id}/")
            assertThat(request.revisionId).isPositive()
            assertThat(request.attemptCount).isEqualTo(1)
        }

        jdbcClient.sql("UPDATE photos SET deleted_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("id", graph.photos[1].id).update()
        val deletedTargetJob = processingJob("QUALITY_ANALYSIS", AdminResourceType.PHOTO, graph.photos[1].id)
        executor.runOnce()
        assertThat(processingState(deletedTargetJob)).isEqualTo(State("CANCELED", 0, "TARGET_DELETED"))
        verify(embeddingInvoker, times(2)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `결과가 없는 stale DISPATCHED는 timeout 실패와 감사를 원자 기록하고 즉시 재호출하지 않는다`() {
        val graph = graph("executor-dispatched-timeout", photoCount = 1)
        val photo = graph.photos.single()
        val jobId = processingJob("QUALITY_ANALYSIS", AdminResourceType.PHOTO, photo.id)
        val shortTimeoutExecutor = AdminWorkflowExecutor(
            repository = workflowExecutionRepository,
            properties = AdminWorkflowExecutorProperties(
                enabled = false,
                retryDelay = Duration.ofMinutes(1),
                staleTimeout = Duration.ofSeconds(1),
                dispatchedTimeout = Duration.ofSeconds(1),
            ),
            embeddingInvoker = embeddingInvoker,
            auditService = auditService,
            transactionTemplate = transactionTemplate,
        )

        shortTimeoutExecutor.runOnce()
        assertThat(processingState(jobId)).isEqualTo(State("DISPATCHED", 1, null))
        jdbcClient.sql(
            "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
        ).param("id", jobId).update()

        shortTimeoutExecutor.runOnce()

        assertThat(processingState(jobId)).isEqualTo(State("FAILED", 1, "DISPATCH_RESULT_TIMEOUT"))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, photo.id)).containsExactly(
            "REPROCESS_DISPATCHED:SUCCESS",
            "REPROCESS_DISPATCH_FAILED:FAILURE",
        )
        verify(embeddingInvoker, times(1)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `임베딩 invoke 결과 불명도 안전 시간이 지나면 실패와 감사로 회수한다`() {
        val graph = graph("executor-ambiguous-invoke", photoCount = 1)
        val jobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            graph.photos.single().id,
            mapOf("galleryId" to graph.gallery.id),
        )
        whenever(embeddingInvoker.invoke(any<ExactPhotoProcessingRequest>()))
            .thenThrow(IllegalStateException("timeout-after-send"))
        val shortTimeoutExecutor = AdminWorkflowExecutor(
            repository = workflowExecutionRepository,
            properties = AdminWorkflowExecutorProperties(
                enabled = false,
                retryDelay = Duration.ofMinutes(1),
                staleTimeout = Duration.ofSeconds(1),
                dispatchedTimeout = Duration.ofSeconds(1),
            ),
            embeddingInvoker = embeddingInvoker,
            auditService = auditService,
            transactionTemplate = transactionTemplate,
        )

        shortTimeoutExecutor.runOnce()

        assertThat(processingState(jobId))
            .isEqualTo(State("DISPATCHING", 1, "DISPATCH_OUTCOME_UNKNOWN"))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, graph.photos.single().id))
            .containsExactly("REPROCESS_DISPATCH_UNKNOWN:FAILURE")

        jdbcClient.sql(
            "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
        ).param("id", jobId).update()
        shortTimeoutExecutor.runOnce()

        assertThat(processingState(jobId))
            .isEqualTo(State("FAILED", 1, "DISPATCH_RESULT_TIMEOUT"))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, graph.photos.single().id)).containsExactly(
            "REPROCESS_DISPATCH_UNKNOWN:FAILURE",
            "REPROCESS_DISPATCH_FAILED:FAILURE",
        )
        verify(embeddingInvoker, times(1)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `중단된 exact photo send start는 timeout 뒤 재시도하거나 취소할 수 있다`() {
        val graph = graph("executor-stale-embedding", photoCount = 2)
        val retryJobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            graph.photos[0].id,
            mapOf("galleryId" to graph.gallery.id),
        )
        val cancelJobId = processingJob(
            "DERIVATIVE",
            AdminResourceType.PHOTO,
            graph.photos[1].id,
            mapOf("galleryId" to graph.gallery.id),
        )
        jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET status='DISPATCHING',attempt_count=1,failure_code=NULL,
                last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day'
            WHERE id IN (:ids)
            """.trimIndent(),
        ).param("ids", listOf(retryJobId, cancelJobId)).update()
        val shortTimeoutExecutor = AdminWorkflowExecutor(
            repository = workflowExecutionRepository,
            properties = AdminWorkflowExecutorProperties(
                enabled = false,
                retryDelay = Duration.ofMinutes(1),
                staleTimeout = Duration.ofSeconds(1),
                dispatchedTimeout = Duration.ofSeconds(1),
            ),
            embeddingInvoker = embeddingInvoker,
            auditService = auditService,
            transactionTemplate = transactionTemplate,
        )

        shortTimeoutExecutor.runOnce()

        assertThat(processingState(retryJobId)).isEqualTo(State("FAILED", 1, "DISPATCH_RESULT_TIMEOUT"))
        assertThat(processingState(cancelJobId)).isEqualTo(State("FAILED", 1, "DISPATCH_RESULT_TIMEOUT"))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, graph.photos[0].id))
            .containsExactly("REPROCESS_DISPATCH_FAILED:FAILURE")
        assertThat(workflowRepository.cancelProcessingJob(cancelJobId)).isEqualTo(1)
        assertThat(processingState(cancelJobId)).isEqualTo(State("CANCELED", 1, null))
        verify(embeddingInvoker, times(0)).invoke(any<ExactPhotoProcessingRequest>())

        jdbcClient.sql(
            "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
        ).param("id", retryJobId).update()
        shortTimeoutExecutor.runOnce()

        assertThat(processingState(retryJobId)).isEqualTo(State("DISPATCHED", 2, null))
        assertThat(processingState(cancelJobId)).isEqualTo(State("CANCELED", 1, null))
        verify(embeddingInvoker).invoke(argThat<ExactPhotoProcessingRequest> {
            jobId == retryJobId && attemptCount == 2
        })
    }

    @Test
    fun `batch 첫 임베딩에서 프로세스가 중단돼도 미전송 claim은 stale 회수하고 각 외부 호출은 한 번만 한다`() {
        val first = graph("executor-batch-first", photoCount = 1)
        val second = graph("executor-batch-second", photoCount = 1)
        val firstJobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            first.photos.single().id,
            mapOf("galleryId" to first.gallery.id),
        )
        val secondJobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            second.photos.single().id,
            mapOf("galleryId" to second.gallery.id),
        )
        whenever(embeddingInvoker.invoke(argThat<ExactPhotoProcessingRequest> { galleryId == first.gallery.id }))
            .thenThrow(AssertionError("simulated process termination"))

        assertThatThrownBy { executor.runOnce() }.isInstanceOf(AssertionError::class.java)

        assertThat(processingState(firstJobId)).isEqualTo(State("DISPATCHING", 1, null))
        assertThat(processingState(secondJobId))
            .isEqualTo(State("DISPATCHING", 0, "CLAIMED_NOT_SENT"))

        jdbcClient.sql(
            "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
        ).param("id", secondJobId).update()
        executor.runOnce()

        assertThat(processingState(firstJobId)).isEqualTo(State("DISPATCHING", 1, null))
        assertThat(processingState(secondJobId)).isEqualTo(State("DISPATCHED", 1, null))
        verify(embeddingInvoker, times(1)).invoke(argThat<ExactPhotoProcessingRequest> { galleryId == first.gallery.id })
        verify(embeddingInvoker, times(1)).invoke(argThat<ExactPhotoProcessingRequest> { galleryId == second.gallery.id })
    }

    @Test
    fun `미전송 claim stale 회수는 attempt를 소모하지 않아 한도가 1이어도 실행 기회를 잃지 않는다`() {
        val graph = graph("executor-lease-attempt", photoCount = 1)
        val jobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            graph.photos.single().id,
            mapOf("galleryId" to graph.gallery.id),
        )

        repeat(5) { index ->
            if (index > 0) {
                jdbcClient.sql(
                    "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
                ).param("id", jobId).update()
            }
            val claimed = workflowExecutionRepository.claimProcessingJobs(
                limit = 1,
                maxAttempts = 1,
                retryDelay = Duration.ZERO,
                staleTimeout = Duration.ofSeconds(1),
            )
            assertThat(claimed.map { it.id }).containsExactly(jobId)
            assertThat(processingState(jobId))
                .isEqualTo(State("DISPATCHING", 0, "CLAIMED_NOT_SENT"))
        }

        jdbcClient.sql(
            "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
        ).param("id", jobId).update()
        executor.runOnce()

        assertThat(processingState(jobId)).isEqualTo(State("DISPATCHED", 1, null))
        verify(embeddingInvoker, times(1)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `마지막 MOCK 실행 시작 뒤 프로세스가 중단되면 stale 회수 후 재실행 없이 소진 실패로 종결한다`() {
        val graph = graph("executor-mock-exhausted", photoCount = 1)
        val selection = create(
            AdminResourceType.SELECTION,
            graph.actorId,
            mapOf("galleryId" to graph.gallery.id),
        )
        val jobId = processingJob(
            "MOCK_RECALCULATION",
            AdminResourceType.SELECTION,
            selection.id,
        )
        val singleAttemptExecutor = AdminWorkflowExecutor(
            repository = workflowExecutionRepository,
            properties = AdminWorkflowExecutorProperties(
                enabled = false,
                maxAttempts = 1,
                retryDelay = Duration.ZERO,
                staleTimeout = Duration.ofSeconds(1),
            ),
            embeddingInvoker = embeddingInvoker,
            auditService = auditService,
            transactionTemplate = transactionTemplate,
        )

        val claimed = workflowExecutionRepository.claimProcessingJobs(
            limit = 1,
            maxAttempts = 1,
            retryDelay = Duration.ZERO,
            staleTimeout = Duration.ofSeconds(1),
        ).single()
        assertThat(
            workflowExecutionRepository.markProcessingExecutionStarted(
                jobId = claimed.id,
                attemptCount = claimed.attemptCount,
                targetType = claimed.targetType,
                targetId = claimed.targetId,
            ),
        ).isEqualTo(1)
        // 여기서 프로세스가 종료된 상태를 재현한다. 로컬 트랜잭션은 시작되지 않았고 lease만 send-start다.
        assertThat(processingState(jobId)).isEqualTo(State("DISPATCHING", 1, null))

        jdbcClient.sql(
            "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
        ).param("id", jobId).update()
        singleAttemptExecutor.runOnce()

        assertThat(processingState(jobId))
            .isEqualTo(State("FAILED", 1, "MAX_ATTEMPTS_EXHAUSTED"))
        assertThat(dispatchAudits(AdminResourceType.SELECTION, selection.id))
            .containsExactly("REPROCESS_DISPATCH_FAILED:FAILURE")
        singleAttemptExecutor.runOnce()
        assertThat(processingState(jobId))
            .isEqualTo(State("FAILED", 1, "MAX_ATTEMPTS_EXHAUSTED"))
        assertThat(dispatchAudits(AdminResourceType.SELECTION, selection.id))
            .containsExactly("REPROCESS_DISPATCH_FAILED:FAILURE")
        verify(embeddingInvoker, times(0)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `임베딩 외부 호출 직전 claim 재확인은 이미 취소된 작업의 호출을 막는다`() {
        val graph = graph("executor-preflight-claim", photoCount = 1)
        val jobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            graph.photos.single().id,
            mapOf("galleryId" to graph.gallery.id),
        )
        whenever(embeddingInvoker.isAvailable).thenAnswer {
            jdbcClient.sql(
                """
                UPDATE admin_processing_jobs
                SET status='CANCELED', failure_code=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=:id
                """.trimIndent(),
            ).param("id", jobId).update()
            true
        }

        executor.runOnce()

        assertThat(processingState(jobId)).isEqualTo(State("CANCELED", 0, null))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, graph.photos.single().id)).isEmpty()
        verify(embeddingInvoker, times(0)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `임베딩 외부 호출 직전 대상 재확인은 삭제된 대상의 claim을 취소한다`() {
        val graph = graph("executor-preflight-target", photoCount = 1)
        val photo = graph.photos.single()
        val jobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            photo.id,
            mapOf("galleryId" to graph.gallery.id),
        )
        whenever(embeddingInvoker.isAvailable).thenAnswer {
            jdbcClient.sql("UPDATE photos SET deleted_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", photo.id).update()
            true
        }

        executor.runOnce()

        assertThat(processingState(jobId)).isEqualTo(State("CANCELED", 0, "TARGET_DELETED"))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, photo.id)).isEmpty()
        verify(embeddingInvoker, times(0)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `임베딩 성공 뒤 불명 감사도 실패하면 상태와 감사를 함께 rollback하고 재호출하지 않는다`() {
        val graph = graph("executor-ambiguous-audit", photoCount = 1)
        val photo = graph.photos.single()
        val jobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            photo.id,
            mapOf("galleryId" to graph.gallery.id),
        )
        val functionName = "test_block_executor_dispatch_audit"
        val triggerName = "test_block_executor_dispatch_audit_trigger"
        jdbcClient.sql(
            """
            CREATE OR REPLACE FUNCTION $functionName()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS ${'$'}trigger${'$'}
            BEGIN
                IF NEW.action IN ('REPROCESS_DISPATCHED', 'REPROCESS_DISPATCH_UNKNOWN')
                   AND NEW.target_type = 'PHOTO'
                   AND NEW.target_id = '${photo.id}' THEN
                    RAISE EXCEPTION 'forced dispatch audit failure';
                END IF;
                RETURN NEW;
            END;
            ${'$'}trigger${'$'}
            """.trimIndent(),
        ).update()
        jdbcClient.sql(
            """
            CREATE TRIGGER $triggerName
            BEFORE INSERT ON admin_audit_logs
            FOR EACH ROW EXECUTE FUNCTION $functionName()
            """.trimIndent(),
        ).update()
        try {
            executor.runOnce()
        } finally {
            jdbcClient.sql("DROP TRIGGER IF EXISTS $triggerName ON admin_audit_logs").update()
            jdbcClient.sql("DROP FUNCTION IF EXISTS $functionName()").update()
        }

        assertThat(processingState(jobId)).isEqualTo(State("DISPATCHING", 1, null))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, photo.id)).isEmpty()
        jdbcClient.sql(
            "UPDATE admin_processing_jobs SET last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE id=:id",
        ).param("id", jobId).update()
        executor.runOnce()
        verify(embeddingInvoker, times(1)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `임베딩 호출 중 cascade 취소가 claim을 이기면 취소를 보존하고 외부 수락 사실을 감사한다`() {
        val graph = graph("executor-cancel-race", photoCount = 1)
        val photo = graph.photos.single()
        val jobId = processingJob(
            "EMBEDDING",
            AdminResourceType.PHOTO,
            photo.id,
            mapOf("galleryId" to graph.gallery.id),
        )
        doAnswer {
            jdbcClient.sql(
                """
                UPDATE admin_processing_jobs
                SET status='CANCELED', failure_code=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=:id
                """.trimIndent(),
            ).param("id", jobId).update()
            null
        }.whenever(embeddingInvoker).invoke(any<ExactPhotoProcessingRequest>())

        executor.runOnce()

        assertThat(processingState(jobId)).isEqualTo(State("CANCELED", 1, null))
        assertThat(dispatchAudits(AdminResourceType.PHOTO, photo.id))
            .containsExactly("REPROCESS_DISPATCHED:SUCCESS")
        verify(embeddingInvoker, times(1)).invoke(any<ExactPhotoProcessingRequest>())
    }

    @Test
    fun `중단된 legacy mock은 stale 회수해 재계산하고 기존 수동 폴더 순서와 crop을 보존한다`() {
        val graph = graph("executor-mock", photoCount = 2)
        val selection = create(AdminResourceType.SELECTION, graph.actorId, mapOf("galleryId" to graph.gallery.id))
        val oldRevisionId = selectionRevision(selection.id, "SUBMITTED", listOf(
            mapOf("photoId" to graph.photos[0].id, "sortOrder" to 0),
        ))
        val submittedRevisionId = selectionRevision(selection.id, "SUBMITTED", listOf(
            mapOf("photoId" to graph.photos[1].id, "sortOrder" to 0),
            mapOf("photoId" to graph.photos[0].id, "sortOrder" to 1),
        ))
        val album = create(
            AdminResourceType.ALBUM,
            graph.actorId,
            mapOf("galleryId" to graph.gallery.id, "name" to "목업 앨범"),
        )
        val templateId = template(graph.studio.id, "공유 목업")
        jdbcClient.sql(
            "UPDATE photo_folder_groups SET template_id=:templateId,template_name='공유 목업',selection_revision_id=:revisionId WHERE id=:albumId",
        ).param("templateId", templateId).param("revisionId", oldRevisionId).param("albumId", album.id).update()
        val legacyFolder = folder(album.id, graph.gallery.id, "이전 목업")
        val manualCrop = mapOf("x" to 0.1, "y" to 0.2, "width" to 0.7, "height" to 0.6)
        folderItem(album.id, legacyFolder, graph.photos[0].id, 7, manualCrop)
        val oldRevisionJson = revisionJson(oldRevisionId)
        val jobId = processingJob(
            "MOCK_RECALCULATION",
            AdminResourceType.SELECTION,
            selection.id,
            revisionId = submittedRevisionId,
        )
        jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET status='DISPATCHING', attempt_count=1, failure_code=NULL,
                last_run_at=CURRENT_TIMESTAMP-INTERVAL '1 day'
            WHERE id=:id
            """.trimIndent(),
        ).param("id", jobId).update()

        executor.runOnce()

        assertThat(processingState(jobId)).isEqualTo(State("SUCCEEDED", 2, null))
        assertThat(
            jdbcClient.sql("SELECT selection_revision_id FROM photo_folder_groups WHERE id=:id")
                .param("id", album.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(submittedRevisionId)
        assertThat(
            jdbcClient.sql(
                "SELECT photo_id,sort_order,crop_json::TEXT FROM photo_folder_items WHERE group_id=:id ORDER BY sort_order,id",
            ).param("id", album.id).query { rs, _ -> Triple(
                rs.getLong("photo_id"), rs.getInt("sort_order"), rs.getString("crop_json"),
            ) }.list(),
        ).satisfiesExactly(
            { item ->
                assertThat(item.first).isEqualTo(graph.photos[0].id)
                assertThat(item.second).isZero()
                assertThat(objectMapper.readTree(item.third)).isEqualTo(objectMapper.valueToTree(manualCrop))
            },
            { item ->
                assertThat(item.first).isEqualTo(graph.photos[1].id)
                assertThat(item.second).isEqualTo(1)
                assertThat(item.third).isNull()
            },
        )
        assertThat(
            jdbcClient.sql("SELECT name FROM photo_folders WHERE group_id=:id")
                .param("id", album.id).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("이전 목업")
        assertThat(revisionJson(oldRevisionId)).isEqualTo(oldRevisionJson)
    }

    @Test
    fun `구조화 템플릿은 새 사진에만 crop을 적용하고 기존 null과 수동 crop을 그대로 보존한다`() {
        val graph = graph("executor-template-layout", photoCount = 4)
        val selection = create(AdminResourceType.SELECTION, graph.actorId, mapOf("galleryId" to graph.gallery.id))
        val firstRevisionId = selectionRevision(selection.id, "SUBMITTED", listOf(
            mapOf("photoId" to graph.photos[2].id, "sortOrder" to 0),
            mapOf("photoId" to graph.photos[0].id, "sortOrder" to 1),
            mapOf("photoId" to graph.photos[1].id, "sortOrder" to 2),
        ))
        val album = create(
            AdminResourceType.ALBUM,
            graph.actorId,
            mapOf("galleryId" to graph.gallery.id, "name" to "템플릿 목업"),
        )
        val coverCrop = mapOf("x" to 0.1, "y" to 0.0, "width" to 0.8, "height" to 1.0)
        val detailCrop = mapOf("x" to 0.0, "y" to 0.25, "width" to 1.0, "height" to 0.5)
        val templateId = template(
            graph.studio.id,
            "구조화 목업",
            mapOf(
                "columns" to 2,
                "folders" to listOf(
                    mapOf("name" to "표지", "items" to listOf(mapOf("sortOrder" to 0, "crop" to coverCrop))),
                    mapOf(
                        "name" to "내지",
                        "items" to listOf(
                            mapOf("sortOrder" to 0, "crop" to detailCrop),
                            mapOf("sortOrder" to 1),
                        ),
                    ),
                ),
            ),
        )
        jdbcClient.sql(
            "UPDATE photo_folder_groups SET template_id=:templateId,template_name='구조화 목업' WHERE id=:albumId",
        ).param("templateId", templateId).param("albumId", album.id).update()

        val firstJobId = processingJob(
            "MOCK_RECALCULATION", AdminResourceType.SELECTION, selection.id, revisionId = firstRevisionId,
        )
        executor.runOnce()

        assertThat(processingState(firstJobId)).isEqualTo(State("SUCCEEDED", 1, null))
        assertThat(albumRows(album.id).map { it.folderName to it.photoId }).containsExactly(
            "표지" to graph.photos[2].id,
            "내지" to graph.photos[0].id,
            "내지" to graph.photos[1].id,
        )
        assertThat(objectMapper.readTree(albumRows(album.id)[0].cropJson)).isEqualTo(objectMapper.valueToTree(coverCrop))
        assertThat(objectMapper.readTree(albumRows(album.id)[1].cropJson)).isEqualTo(objectMapper.valueToTree(detailCrop))

        val manualCrop = mapOf("x" to 0.2, "y" to 0.2, "width" to 0.6, "height" to 0.6)
        jdbcClient.sql(
            "UPDATE photo_folder_items SET crop_json=CAST(:crop AS JSONB) WHERE group_id=:albumId AND photo_id=:photoId",
        ).param("crop", objectMapper.writeValueAsString(manualCrop)).param("albumId", album.id)
            .param("photoId", graph.photos[0].id).update()
        jdbcClient.sql(
            "UPDATE photo_folder_items SET crop_json=NULL WHERE group_id=:albumId AND photo_id=:photoId",
        ).param("albumId", album.id).param("photoId", graph.photos[2].id).update()
        val secondRevisionId = selectionRevision(selection.id, "SUBMITTED", listOf(
            mapOf("photoId" to graph.photos[2].id, "sortOrder" to 0),
            mapOf("photoId" to graph.photos[0].id, "sortOrder" to 1),
            mapOf("photoId" to graph.photos[1].id, "sortOrder" to 2),
            mapOf("photoId" to graph.photos[3].id, "sortOrder" to 3),
        ))
        val secondJobId = processingJob(
            "MOCK_RECALCULATION", AdminResourceType.SELECTION, selection.id, revisionId = secondRevisionId,
        )
        executor.runOnce()

        assertThat(processingState(secondJobId)).isEqualTo(State("SUCCEEDED", 1, null))
        val rebuilt = albumRows(album.id)
        assertThat(rebuilt.map { it.folderName to it.photoId }).containsExactly(
            "표지" to graph.photos[2].id,
            "내지" to graph.photos[0].id,
            "내지" to graph.photos[1].id,
            "내지" to graph.photos[3].id,
        )
        assertThat(rebuilt[0].cropJson).isNull()
        assertThat(objectMapper.readTree(rebuilt[1].cropJson)).isEqualTo(objectMapper.valueToTree(manualCrop))
        assertThat(rebuilt[3].cropJson).isNull()
    }

    @Test
    fun `mock 대상에 템플릿 앨범이 없으면 기존 레이아웃을 보존하고 실패한다`() {
        val graph = graph("executor-no-album", photoCount = 1)
        val selection = create(AdminResourceType.SELECTION, graph.actorId, mapOf("galleryId" to graph.gallery.id))
        val revisionId = selectionRevision(selection.id, "SUBMITTED", listOf(
            mapOf("photoId" to graph.photos.single().id, "sortOrder" to 0),
        ))
        val album = create(
            AdminResourceType.ALBUM,
            graph.actorId,
            mapOf("galleryId" to graph.gallery.id, "name" to "템플릿 없는 앨범"),
        )
        val existingFolder = folder(album.id, graph.gallery.id, "보존 폴더")
        folderItem(album.id, existingFolder, graph.photos.single().id, 7)
        val jobId = processingJob(
            "MOCK_RECALCULATION", AdminResourceType.SELECTION, selection.id, revisionId = revisionId,
        )

        executor.runOnce()

        assertThat(processingState(jobId)).isEqualTo(State("FAILED", 1, "NO_TEMPLATE_ALBUM"))
        assertThat(
            jdbcClient.sql("SELECT name FROM photo_folders WHERE id=:id")
                .param("id", existingFolder).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("보존 폴더")
        assertThat(
            jdbcClient.sql("SELECT sort_order FROM photo_folder_items WHERE group_id=:id")
                .param("id", album.id).query { rs, _ -> rs.getInt(1) }.single(),
        ).isEqualTo(7)
    }

    private fun graph(suffix: String, photoCount: Int): Graph {
        val actor = adminAccountFixture.관리자(suffix)
        val user = create(AdminResourceType.USER, actor.requiredId, mapOf(
            "provider" to "GOOGLE", "providerId" to suffix, "nickname" to suffix,
        ))
        val studio = create(AdminResourceType.STUDIO, actor.requiredId, mapOf(
            "ownerUserId" to user.id, "name" to suffix, "galleryUrl" to suffix,
        ))
        val gallery = create(AdminResourceType.GALLERY, actor.requiredId, mapOf(
            "workspaceId" to studio.id, "title" to suffix,
        ))
        val photos = (1..photoCount).map { index ->
            create(AdminResourceType.PHOTO, actor.requiredId, mapOf(
                "galleryId" to gallery.id,
                "storageKey" to "galleries/${gallery.id}/$suffix-$index.jpg",
                "originalFileName" to "$suffix-$index.jpg",
                "contentType" to "image/jpeg",
                "status" to "UPLOADED",
            ))
        }
        return Graph(actor.requiredId, studio, gallery, photos)
    }

    private fun create(type: AdminResourceType, actorId: Long, fields: Map<String, Any?>): AdminResourceResponse =
        resourceService.create(actorId, type, CreateAdminResourceRequest("executor test", fields), "127.0.0.1")

    private fun processingJob(
        jobType: String,
        targetType: AdminResourceType,
        targetId: Long,
        payload: Map<String, Any?> = emptyMap(),
        revisionId: Long? = null,
    ): Long {
        val isExactPhotoJob = targetType == AdminResourceType.PHOTO &&
            jobType in setOf("DERIVATIVE", "EMBEDDING", "QUALITY_ANALYSIS")
        val photo = if (isExactPhotoJob) {
            jdbcClient.sql(
                """
                SELECT gallery_id,storage_key,preview_key,original_file_name,content_type
                FROM photos WHERE id=:photoId
                """.trimIndent(),
            ).param("photoId", targetId).query { rs, _ -> PhotoJobSeed(
                galleryId = rs.getLong("gallery_id"),
                storageKey = rs.getString("storage_key"),
                previewKey = rs.getString("preview_key"),
                originalFileName = rs.getString("original_file_name"),
                contentType = rs.getString("content_type"),
            ) }.single()
        } else {
            null
        }
        val effectiveRevisionId = if (photo != null && revisionId == null) {
            jdbcClient.sql(
                """
                INSERT INTO admin_photo_revisions
                    (photo_id,revision_number,storage_key,preview_key,original_file_name,content_type,created_at)
                VALUES (:photoId,
                        (SELECT COALESCE(MAX(revision_number),0)+1 FROM admin_photo_revisions WHERE photo_id=:photoId),
                        :storageKey,:previewKey,:originalFileName,:contentType,CURRENT_TIMESTAMP)
                RETURNING id
                """.trimIndent(),
            ).param("photoId", targetId).param("storageKey", photo.storageKey)
                .param("previewKey", photo.previewKey).param("originalFileName", photo.originalFileName)
                .param("contentType", photo.contentType).query { rs, _ -> rs.getLong(1) }.single()
        } else {
            revisionId
        }
        val effectivePayload = payload.toMutableMap()
        photo?.let {
            effectivePayload.putIfAbsent("galleryId", it.galleryId)
            effectivePayload.putIfAbsent("storageKey", it.storageKey)
        }
        return jdbcClient.sql(
            """
            INSERT INTO admin_processing_jobs
                (job_type,status,target_type,target_id,revision_id,payload,attempt_count,reason,created_at,updated_at)
            VALUES (:jobType,'PENDING',:targetType,:targetId,:revisionId,CAST(:payload AS JSONB),0,
                    'reasonCategory=TEST_OPERATION operatorReasonProvided=true',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("jobType", jobType).param("targetType", targetType.name).param("targetId", targetId)
            .param("revisionId", effectiveRevisionId)
            .param("payload", objectMapper.writeValueAsString(effectivePayload))
            .query { rs, _ -> rs.getLong(1) }.single()
    }

    private fun notification(sourceType: AdminResourceType, sourceId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO admin_notification_outbox
            (notification_type,recipient_reference,source_type,source_id,payload,status,attempt_count,reason,created_at,updated_at)
        VALUES ('TEST','user:1',:sourceType,:sourceId,'{}'::JSONB,'PENDING',0,
                'reasonCategory=TEST_OPERATION operatorReasonProvided=true',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    ).param("sourceType", sourceType.name).param("sourceId", sourceId)
        .query { rs, _ -> rs.getLong(1) }.single()

    private fun selectionRevision(selectionId: Long, status: String, items: List<Map<String, Any?>>): Long =
        jdbcClient.sql(
            """
            INSERT INTO admin_selection_revisions
                (selection_id,revision_number,source,status,photo_items,reason,created_at)
            VALUES (:selectionId,
                    (SELECT COALESCE(MAX(revision_number),0)+1 FROM admin_selection_revisions WHERE selection_id=:selectionId),
                    'ADMIN',:status,CAST(:items AS JSONB),
                    'reasonCategory=TEST_OPERATION operatorReasonProvided=true',CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("selectionId", selectionId).param("status", status)
            .param("items", objectMapper.writeValueAsString(items))
            .query { rs, _ -> rs.getLong(1) }.single()

    private fun template(
        studioId: Long,
        name: String,
        layout: Map<String, Any?> = emptyMap(),
    ): Long = jdbcClient.sql(
        """
        INSERT INTO admin_album_templates (studio_id,name,layout_json,version,created_at,updated_at)
        VALUES (:studioId,:name,CAST(:layout AS JSONB),0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
        """.trimIndent(),
    ).param("studioId", studioId).param("name", name).param("layout", objectMapper.writeValueAsString(layout))
        .query { rs, _ -> rs.getLong(1) }.single()

    private fun folder(albumId: Long, galleryId: Long, name: String): Long = jdbcClient.sql(
        """
        INSERT INTO photo_folders (group_id,gallery_id,name,version,created_at,updated_at)
        VALUES (:albumId,:galleryId,:name,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
        """.trimIndent(),
    ).param("albumId", albumId).param("galleryId", galleryId).param("name", name)
        .query { rs, _ -> rs.getLong(1) }.single()

    private fun folderItem(
        albumId: Long,
        folderId: Long,
        photoId: Long,
        sortOrder: Int,
        crop: Map<String, Any?>? = null,
    ) {
        jdbcClient.sql(
            """
            INSERT INTO photo_folder_items
                (group_id,folder_id,photo_id,sort_order,crop_json,version,created_at,updated_at)
            VALUES (:albumId,:folderId,:photoId,:sortOrder,CAST(:crop AS JSONB),0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("albumId", albumId).param("folderId", folderId).param("photoId", photoId)
            .param("sortOrder", sortOrder).param("crop", crop?.let(objectMapper::writeValueAsString) ?: "null").update()
    }

    private fun albumRows(albumId: Long): List<AlbumRow> = jdbcClient.sql(
        """
        SELECT folder.name, item.photo_id, item.sort_order, item.crop_json::TEXT
        FROM photo_folders folder
        JOIN photo_folder_items item ON item.folder_id = folder.id
        WHERE folder.group_id = :albumId
        ORDER BY folder.id, item.sort_order, item.id
        """.trimIndent(),
    ).param("albumId", albumId).query { rs, _ -> AlbumRow(
        rs.getString("name"), rs.getLong("photo_id"), rs.getInt("sort_order"), rs.getString("crop_json"),
    ) }.list()

    private fun processingState(id: Long): State = jdbcClient.sql(
        "SELECT status,attempt_count,failure_code FROM admin_processing_jobs WHERE id=:id",
    ).param("id", id).query { rs, _ -> State(
        rs.getString("status"), rs.getInt("attempt_count"), rs.getString("failure_code"),
    ) }.single()

    private fun notificationState(): State = jdbcClient.sql(
        "SELECT status,attempt_count,failure_code FROM admin_notification_outbox",
    ).query { rs, _ -> State(
        rs.getString("status"), rs.getInt("attempt_count"), rs.getString("failure_code"),
    ) }.single()

    private fun dispatchAudits(type: AdminResourceType, id: Long): List<String> = jdbcClient.sql(
        """
        SELECT action || ':' || outcome
        FROM admin_audit_logs
        WHERE target_type = :targetType AND target_id = :targetId
          AND action IN (
            'REPROCESS_DISPATCHED', 'REPROCESS_DISPATCH_FAILED', 'REPROCESS_DISPATCH_UNKNOWN'
          )
        ORDER BY id
        """.trimIndent(),
    ).param("targetType", type.auditTargetType.name).param("targetId", id.toString())
        .query { rs, _ -> rs.getString(1) }.list()

    private fun revisionJson(id: Long): String = jdbcClient.sql(
        "SELECT photo_items::TEXT FROM admin_selection_revisions WHERE id=:id",
    ).param("id", id).query { rs, _ -> rs.getString(1) }.single()

    private data class Graph(
        val actorId: Long,
        val studio: AdminResourceResponse,
        val gallery: AdminResourceResponse,
        val photos: List<AdminResourceResponse>,
    )

    private data class State(val status: String, val attempts: Int, val failureCode: String?)
    private data class AlbumRow(val folderName: String, val photoId: Long, val sortOrder: Int, val cropJson: String?)
    private data class PhotoJobSeed(
        val galleryId: Long,
        val storageKey: String,
        val previewKey: String?,
        val originalFileName: String,
        val contentType: String,
    )
}
