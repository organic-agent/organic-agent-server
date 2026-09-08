package com.soma.wes.admin.resource

import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactAccessMode
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactAccessRequest
import com.soma.wes.admin.resource.dto.AdminRetouchArtifactType
import com.soma.wes.admin.resource.dto.AdminWorkflowAction
import com.soma.wes.admin.resource.dto.AdminWorkflowRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceContextService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.resource.service.AdminRetouchArtifactAccessService
import com.soma.wes.admin.resource.service.AdminWorkflowService
import com.soma.wes.photo.dto.PresignedUploadDto
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.support.IntegrationTest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager

@IntegrationTest
class AdminRetouchArtifactServiceTest @Autowired constructor(
    private val workflowService: AdminWorkflowService,
    private val resourceService: AdminResourceService,
    private val contextService: AdminResourceContextService,
    private val accessService: AdminRetouchArtifactAccessService,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcClient: JdbcClient,
) {

    @MockitoBean
    private lateinit var photoStorage: PhotoStorage

    @Test
    fun `보정 표식과 결과는 scoped 2단계 업로드와 transient 감사 접근을 거쳐 null로 명시 삭제된다`() {
        val actor = adminAccountFixture.관리자("retouch-artifact-owner")
        val graph = createGraph(actor.requiredId)
        val round = resourceService.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 보정 산출물 테스트 회차",
                mapOf("galleryId" to graph.galleryId, "roundNo" to 1),
            ),
            "127.0.0.1",
        )
        val createdItem = execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.CREATE_RETOUCH_ITEM,
            0,
            "retouch-artifact-item-001",
            mapOf(
                "photoId" to graph.photoId,
                "requestText" to "얼굴 주위만 자연스럽게",
                "structuredAiMetadata" to mapOf("kind" to "SKIN", "strength" to 0.2),
            ),
        )
        val itemId = (createdItem.details.getValue("retouchPhotoId") as Number).toLong()

        val signedKeys = mutableListOf<String>()
        var signatureSequence = 0
        whenever(photoStorage.presignUpload(any(), any(), anyOrNull())).thenAnswer { invocation ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            val storageKey = invocation.getArgument<String>(0)
            signedKeys += storageKey
            signatureSequence += 1
            PresignedUploadDto(
                "https://upload.example/$signatureSequence?signature=private",
                Instant.now().plusSeconds(3_600),
            )
        }
        whenever(photoStorage.exists(any())).thenAnswer { invocation ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            signedKeys.contains(invocation.getArgument<String>(0))
        }

        val annotationRequest = request(
            AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD,
            expectedVersion = 1,
            idempotencyKey = "retouch-annotation-issue-001",
            fields = mapOf(
                "retouchPhotoId" to itemId,
                "retouchPhotoExpectedVersion" to 0,
                "artifactType" to "ANNOTATION",
                "fileName" to "private-annotation.png",
                "contentType" to "image/png",
            ),
        )
        val annotationIssue = workflowService.execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            round.id,
            annotationRequest,
            "127.0.0.1",
        )
        val annotationReplay = workflowService.execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            round.id,
            annotationRequest,
            "127.0.0.1",
        )
        val annotationKey = signedKeys.first()
        assertThat(annotationKey).startsWith(
            "galleries/${graph.galleryId}/retouch/annotations/1/$itemId/",
        ).endsWith(".png")
        assertThat(annotationReplay.replayed).isTrue()
        assertThat(annotationReplay.details["uploadUrl"]).isNotEqualTo(annotationIssue.details["uploadUrl"])
        assertThat(signedKeys[1]).isEqualTo(annotationKey)
        assertThat(annotationIssue.details).containsEntry("roundVersion", 2L)
            .containsEntry("retouchPhotoVersion", 1L)
            .doesNotContainKey("storageKey")

        val persistedIssue = jdbcClient.sql(
            """
            SELECT result_payload
            FROM admin_idempotency_keys
            WHERE action = 'WORKFLOW_ISSUE_RETOUCH_ARTIFACT_UPLOAD'
              AND idempotency_key = 'retouch-annotation-issue-001'
            """.trimIndent(),
        ).query { rs, _ -> rs.getString(1) }.single()
        assertThat(persistedIssue)
            .doesNotContain("uploadUrl", "https://", "galleries/", "storageKey")
            .contains("uploadId", "roundVersion", "retouchPhotoVersion")
        assertThat(
            jdbcClient.sql(
                "SELECT reason FROM admin_retouch_artifact_uploads WHERE id = :id",
            )
                .param("id", number(annotationIssue.details, "uploadId"))
                .query { rs, _ -> rs.getString(1) }
                .single(),
        ).isEqualTo("reasonCategory=TEST_OPERATION operatorReasonProvided=true")

        execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.COMPLETE_RETOUCH_ARTIFACT_UPLOAD,
            2,
            "retouch-annotation-complete-001",
            mapOf(
                "uploadId" to number(annotationIssue.details, "uploadId"),
                "retouchPhotoId" to itemId,
                "retouchPhotoExpectedVersion" to 1,
            ),
        )

        whenever(photoStorage.presignView(annotationKey)).thenReturn("https://view.example/transient")
        val viewed = accessService.access(
            actor.requiredId,
            round.id,
            itemId,
            AdminRetouchArtifactType.ANNOTATION,
            AdminRetouchArtifactAccessRequest(
                reason = "[TEST_OPERATION] 표식 열람 민감 상세",
                mode = AdminRetouchArtifactAccessMode.VIEW,
            ),
            "127.0.0.1",
        )
        assertThat(viewed.url).isEqualTo("https://view.example/transient")
        assertThat(viewed.originalFileName).isEqualTo("private-annotation.png")
        assertThat(viewed.toString()).doesNotContain(annotationKey)
        verify(photoStorage).presignView(annotationKey)
        val accessAudit = jdbcClient.sql(
            """
            SELECT target_id, reason
            FROM admin_audit_logs
            WHERE action = 'RETOUCH_ARTIFACT_VIEWED'
            ORDER BY id DESC LIMIT 1
            """.trimIndent(),
        ).query { rs, _ -> rs.getString("target_id") to rs.getString("reason") }.single()
        assertThat(accessAudit.first).isEqualTo("RA-${round.id}-$itemId-ANNOTATION")
        assertThat(accessAudit.second).isEqualTo("reasonCategory=TEST_OPERATION operatorReasonProvided=true")

        // DRAFTING에서는 고객 요청·표식·AI metadata만 바꿀 수 있다.
        execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
            3,
            "retouch-drafting-clear-001",
            linkedMapOf(
                "retouchPhotoId" to itemId,
                "retouchPhotoExpectedVersion" to 2,
                "requestText" to null,
                "annotationKey" to null,
                "structuredAiMetadata" to null,
            ),
        )
        assertThatThrownBy {
            execute(
                actor.requiredId,
                round.id,
                AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD,
                4,
                "retouch-result-drafting-rejected-001",
                mapOf(
                    "retouchPhotoId" to itemId,
                    "retouchPhotoExpectedVersion" to 3,
                    "artifactType" to "RESULT",
                    "fileName" to "too-early.webp",
                    "contentType" to "image/webp",
                ),
            )
        }.isInstanceOf(AdminException::class.java)

        val requestedRound = resourceService.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            round.id,
            UpdateAdminResourceRequest(
                reason = "[TEST_OPERATION] 작가 결과 업로드 단계 전환",
                expectedVersion = 4,
                fields = mapOf("status" to "REQUESTED"),
            ),
            "127.0.0.1",
        )
        assertThat(requestedRound.version).isEqualTo(5)
        assertThatThrownBy {
            execute(
                actor.requiredId,
                round.id,
                AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD,
                5,
                "retouch-annotation-requested-rejected-001",
                mapOf(
                    "retouchPhotoId" to itemId,
                    "retouchPhotoExpectedVersion" to 3,
                    "artifactType" to "ANNOTATION",
                    "fileName" to "too-late.png",
                    "contentType" to "image/png",
                ),
            )
        }.isInstanceOf(AdminException::class.java)
        assertThatThrownBy {
            execute(
                actor.requiredId,
                round.id,
                AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
                5,
                "retouch-request-text-requested-rejected-001",
                mapOf(
                    "retouchPhotoId" to itemId,
                    "retouchPhotoExpectedVersion" to 3,
                    "requestText" to "제출 뒤 요청 변경",
                ),
            )
        }.isInstanceOf(AdminException::class.java)

        // REQUESTED에서는 작가 결과만 발급·확정·제거할 수 있다.
        val resultIssue = execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD,
            5,
            "retouch-result-issue-001",
            mapOf(
                "retouchPhotoId" to itemId,
                "retouchPhotoExpectedVersion" to 3,
                "artifactType" to "RESULT",
                "fileName" to "private-result.webp",
                "contentType" to "image/webp",
            ),
        )
        val resultKey = signedKeys.last()
        assertThat(resultKey).startsWith(
            "galleries/${graph.galleryId}/retouch/results/1/$itemId/",
        ).endsWith(".webp")
        execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.COMPLETE_RETOUCH_ARTIFACT_UPLOAD,
            6,
            "retouch-result-complete-001",
            mapOf(
                "uploadId" to number(resultIssue.details, "uploadId"),
                "retouchPhotoId" to itemId,
                "retouchPhotoExpectedVersion" to 4,
            ),
        )
        execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
            7,
            "retouch-result-clear-001",
            linkedMapOf(
                "retouchPhotoId" to itemId,
                "retouchPhotoExpectedVersion" to 5,
                "resultKey" to null,
                "resultContentType" to null,
            ),
        )
        val completedRound = resourceService.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            round.id,
            UpdateAdminResourceRequest(
                reason = "[TEST_OPERATION] 보정 회차 완료",
                expectedVersion = 8,
                fields = mapOf("status" to "COMPLETED"),
            ),
            "127.0.0.1",
        )
        assertThat(completedRound.version).isEqualTo(9)
        assertThatThrownBy {
            execute(
                actor.requiredId,
                round.id,
                AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
                9,
                "retouch-result-completed-rejected-001",
                mapOf(
                    "retouchPhotoId" to itemId,
                    "retouchPhotoExpectedVersion" to 6,
                    "resultKey" to null,
                    "resultContentType" to null,
                ),
            )
        }.isInstanceOf(AdminException::class.java)

        val cleared = jdbcClient.sql(
            """
            SELECT request_text IS NULL, annotation_key IS NULL, result_key IS NULL,
                   result_content_type IS NULL, structured_ai_metadata IS NULL, version
            FROM retouch_photos WHERE id = :id
            """.trimIndent(),
        )
            .param("id", itemId)
            .query { rs, _ -> ClearState(
                requestTextCleared = rs.getBoolean(1),
                annotationCleared = rs.getBoolean(2),
                resultCleared = rs.getBoolean(3),
                resultContentTypeCleared = rs.getBoolean(4),
                metadataCleared = rs.getBoolean(5),
                version = rs.getLong(6),
            ) }
            .single()
        assertThat(cleared).isEqualTo(ClearState(true, true, true, true, true, 6))
        assertThat(resourceService.get(AdminResourceType.RETOUCH_REQUEST, round.id).version).isEqualTo(9)

        val contextText = contextService.get(AdminResourceType.RETOUCH_REQUEST, round.id).toString()
        assertThat(contextText).doesNotContain(annotationKey, resultKey, "https://")
        val permanentSnapshots = jdbcClient.sql(
            """
            SELECT COALESCE(string_agg(COALESCE(before_snapshot, '') || COALESCE(after_snapshot, ''), ''), '')
            FROM admin_entity_revisions
            WHERE target_type = 'RETOUCH_REQUEST' AND target_id = :targetId
            """.trimIndent(),
        )
            .param("targetId", round.id.toString())
            .query { rs, _ -> rs.getString(1) }
            .single()
        assertThat(permanentSnapshots).doesNotContain(annotationKey, resultKey, "https://upload.example")
    }

    @Test
    fun `표식 업로드를 발급한 뒤 회차가 제출되면 완료를 거절하고 pending 연결을 유지한다`() {
        val actor = adminAccountFixture.관리자("retouch-artifact-lifecycle-owner")
        val graph = createGraph(actor.requiredId)
        val round = resourceService.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 표식 완료 단계 경계 회차",
                mapOf("galleryId" to graph.galleryId, "roundNo" to 1),
            ),
            "127.0.0.1",
        )
        val createdItem = execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.CREATE_RETOUCH_ITEM,
            0,
            "retouch-annotation-lifecycle-item-001",
            mapOf("photoId" to graph.photoId),
        )
        val itemId = number(createdItem.details, "retouchPhotoId")

        whenever(photoStorage.presignUpload(any(), any(), anyOrNull())).thenReturn(
            PresignedUploadDto(
                "https://upload.example/annotation-lifecycle?signature=private",
                Instant.now().plusSeconds(3_600),
            ),
        )
        val issued = execute(
            actor.requiredId,
            round.id,
            AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD,
            1,
            "retouch-annotation-lifecycle-issue-001",
            mapOf(
                "retouchPhotoId" to itemId,
                "retouchPhotoExpectedVersion" to 0,
                "artifactType" to "ANNOTATION",
                "fileName" to "annotation-before-request.png",
                "contentType" to "image/png",
            ),
        )
        val uploadId = number(issued.details, "uploadId")

        val requestedRound = resourceService.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            round.id,
            UpdateAdminResourceRequest(
                reason = "[TEST_OPERATION] 표식 발급 뒤 제출",
                expectedVersion = 2,
                fields = mapOf("status" to "REQUESTED"),
            ),
            "127.0.0.1",
        )
        assertThat(requestedRound.version).isEqualTo(3)
        whenever(photoStorage.exists(any())).thenReturn(true)

        assertThatThrownBy {
            execute(
                actor.requiredId,
                round.id,
                AdminWorkflowAction.COMPLETE_RETOUCH_ARTIFACT_UPLOAD,
                3,
                "retouch-annotation-lifecycle-complete-001",
                mapOf(
                    "uploadId" to uploadId,
                    "retouchPhotoId" to itemId,
                    "retouchPhotoExpectedVersion" to 1,
                ),
            )
        }.isInstanceOf(AdminException::class.java)

        val persisted = jdbcClient.sql(
            """
            SELECT u.status, p.annotation_key, r.version AS round_version, p.version AS item_version
            FROM admin_retouch_artifact_uploads u
            JOIN retouch_photos p ON p.id = u.retouch_photo_id
            JOIN retouch_rounds r ON r.id = u.round_id
            WHERE u.id = :uploadId
            """.trimIndent(),
        )
            .param("uploadId", uploadId)
            .query { rs, _ -> LifecycleState(
                uploadStatus = rs.getString("status"),
                annotationKey = rs.getString("annotation_key"),
                roundVersion = rs.getLong("round_version"),
                itemVersion = rs.getLong("item_version"),
            ) }
            .single()
        assertThat(persisted).isEqualTo(LifecycleState("PENDING", null, 3, 1))
    }

    private fun execute(
        actorAdminId: Long,
        roundId: Long,
        action: AdminWorkflowAction,
        expectedVersion: Long,
        idempotencyKey: String,
        fields: Map<String, Any?>,
    ) = workflowService.execute(
        actorAdminId,
        AdminResourceType.RETOUCH_REQUEST,
        roundId,
        request(action, expectedVersion, idempotencyKey, fields),
        "127.0.0.1",
    )

    private fun request(
        action: AdminWorkflowAction,
        expectedVersion: Long,
        idempotencyKey: String,
        fields: Map<String, Any?>,
    ) = AdminWorkflowRequest(
        action = action,
        reason = "[TEST_OPERATION] 민감 상세 private@example.com",
        expectedVersion = expectedVersion,
        idempotencyKey = idempotencyKey,
        confirm = true,
        fields = fields,
    )

    private fun createGraph(actorAdminId: Long): Graph {
        val user = resourceService.create(
            actorAdminId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 산출물 사용자",
                mapOf(
                    "provider" to "GOOGLE",
                    "providerId" to "retouch-artifact-owner",
                    "nickname" to "산출물 사용자",
                ),
            ),
            "127.0.0.1",
        )
        val studio = resourceService.create(
            actorAdminId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 산출물 스튜디오",
                mapOf(
                    "ownerUserId" to user.id,
                    "name" to "산출물 스튜디오",
                    "galleryUrl" to "retouch-artifact-gallery",
                ),
            ),
            "127.0.0.1",
        )
        val gallery = resourceService.create(
            actorAdminId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 산출물 갤러리",
                mapOf("workspaceId" to studio.id, "title" to "산출물 갤러리"),
            ),
            "127.0.0.1",
        )
        val photo = resourceService.create(
            actorAdminId,
            AdminResourceType.PHOTO,
            CreateAdminResourceRequest(
                "[TEST_OPERATION] 산출물 사진",
                mapOf(
                    "galleryId" to gallery.id,
                    "storageKey" to "galleries/${gallery.id}/source.jpg",
                    "originalFileName" to "source.jpg",
                    "contentType" to "image/jpeg",
                    "status" to "UPLOADED",
                ),
            ),
            "127.0.0.1",
        )
        return Graph(gallery.id, photo.id)
    }

    private fun number(details: Map<String, Any?>, name: String): Long =
        (details[name] as Number).toLong()

    private data class Graph(val galleryId: Long, val photoId: Long)
    private data class ClearState(
        val requestTextCleared: Boolean,
        val annotationCleared: Boolean,
        val resultCleared: Boolean,
        val resultContentTypeCleared: Boolean,
        val metadataCleared: Boolean,
        val version: Long,
    )
    private data class LifecycleState(
        val uploadStatus: String,
        val annotationKey: String?,
        val roundVersion: Long,
        val itemVersion: Long,
    )
}
