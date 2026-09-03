package com.soma.wes.admin.resource

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.AdminWorkflowAction
import com.soma.wes.admin.resource.dto.AdminWorkflowRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceContextService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.resource.service.AdminObservabilityLinkService
import com.soma.wes.admin.resource.service.AdminOperationsOverviewService
import com.soma.wes.admin.resource.service.AdminWorkflowService
import com.soma.wes.admin.resource.repository.AdminChildTrashRepository
import com.soma.wes.embedding.service.EmbeddingInvoker
import com.soma.wes.photo.dto.PresignedUploadDto
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.support.IntegrationTest
import com.soma.wes.global.filter.HttpLoggingFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@IntegrationTest
class AdminWorkflowServiceTest @Autowired constructor(
    private val service: AdminWorkflowService,
    private val resourceService: AdminResourceService,
    private val contextService: AdminResourceContextService,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcClient: JdbcClient,
    private val operationsOverviewService: AdminOperationsOverviewService,
    private val observabilityLinkService: AdminObservabilityLinkService,
    private val childTrashRepository: AdminChildTrashRepository,
    private val transactionTemplate: TransactionTemplate,
) {

    @MockitoBean
    private lateinit var photoStorage: PhotoStorage

    @MockitoBean
    private lateinit var embeddingInvoker: EmbeddingInvoker

    @BeforeEach
    fun setUp() {
        whenever(embeddingInvoker.isAvailable).thenReturn(true)
    }

    @Test
    fun `관리자 분류 실행은 사용자 위임 없이 초기 증분 작업을 만들고 갤러리 버전을 올린다`() {
        val actor = adminAccountFixture.관리자("workflow-categorization")
        val graph = createGraph(actor.requiredId, "categorization", withPhoto = true)
        prepareAiCategoryAnalysis(graph.gallery.id, graph.photo!!.id)

        val initial = execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.RUN_CATEGORIZATION,
            graph.gallery.version,
            "categorization-initial-001",
        )

        assertThat(initial.details)
            .containsEntry("mode", "INITIAL")
            .containsEntry("jobStatus", "SUCCEEDED")
            .containsEntry("processedPhotoCount", 1)
        val initialJobId = (initial.details.getValue("jobId") as Number).toLong()
        assertThat(resourceService.get(AdminResourceType.CATEGORIZATION_JOB, initialJobId).fields)
            .containsEntry("galleryId", graph.gallery.id)
            .containsEntry("status", "SUCCEEDED")
        assertThat(
            contextService.get(AdminResourceType.GALLERY, graph.gallery.id)
                .sections.getValue("categorizationJobs").map { it["id"] },
        )
            .contains(initialJobId)

        val incremental = execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.RUN_CATEGORIZATION,
            expectedVersion = 1,
            idempotencyKey = "categorization-incremental-001",
        )

        assertThat(incremental.details)
            .containsEntry("mode", "INCREMENTAL")
            .containsEntry("jobStatus", "SUCCEEDED")
            .containsEntry("processedPhotoCount", 0)
        assertThat(resourceService.get(AdminResourceType.GALLERY, graph.gallery.id).version).isEqualTo(2)
    }

    @Test
    fun `액션 계약은 잘못된 대상과 필드를 예약 전에 거절하고 완료 응답을 멱등 재생한다`() {
        val actor = adminAccountFixture.관리자("workflow-contract-owner")
        val graph = createGraph(actor.requiredId, "contract")

        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.USER,
                graph.user.id,
                AdminWorkflowAction.SET_STUDIO_OWNER,
                graph.user.version,
                "wrong-target-001",
                mapOf("userId" to graph.user.id),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.STUDIO,
                graph.studio.id,
                AdminWorkflowAction.SET_STUDIO_RETOUCH_CAPABILITY,
                graph.studio.version,
                "unknown-field-001",
                mapOf("capability" to "AI_RETOUCH", "enabled" to true, "typo" to true),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.GALLERY,
                graph.gallery.id,
                AdminWorkflowAction.UPDATE_GALLERY_STATES,
                graph.gallery.version,
                "gallery-stage-bypass-001",
                mapOf(
                    "publicStatus" to "OPEN",
                    "workflowStatus" to "IN_PROGRESS",
                    "stage" to "RETOUCH",
                ),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM admin_idempotency_keys")
                .query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()

        jdbcClient.sql(
            """
            INSERT INTO refresh_tokens (user_id, token, expires_at, version, created_at, updated_at)
            VALUES (:userId, 'refresh-token', CURRENT_TIMESTAMP + INTERVAL '1 day', 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("userId", graph.user.id).update()
        val terminated = execute(
            actor.requiredId,
            AdminResourceType.USER,
            graph.user.id,
            AdminWorkflowAction.TERMINATE_USER_SESSIONS,
            graph.user.version,
            "terminate-sessions-001",
        )
        assertThat(terminated.details["terminatedCount"]).isEqualTo(1)
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = :userId")
                .param("userId", graph.user.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()

        val request = AdminWorkflowRequest(
            action = AdminWorkflowAction.SET_STUDIO_RETOUCH_CAPABILITY,
            reason = "스튜디오 보정 기능 활성화",
            expectedVersion = graph.studio.version,
            idempotencyKey = "capability-idem-001",
            confirm = true,
            fields = mapOf("capability" to "AI_RETOUCH", "enabled" to true),
        )
        val first = service.execute(
            actor.requiredId,
            AdminResourceType.STUDIO,
            graph.studio.id,
            request,
            "127.0.0.1",
        )
        val replay = service.execute(
            actor.requiredId,
            AdminResourceType.STUDIO,
            graph.studio.id,
            request,
            "127.0.0.1",
        )

        assertThat(first.status).isEqualTo("COMPLETED")
        assertThat(replay.replayed).isTrue()
        assertThat(resourceService.get(AdminResourceType.STUDIO, graph.studio.id).version).isEqualTo(1)
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM studio_retouch_capabilities WHERE studio_id = :studioId AND enabled",
            ).param("studioId", graph.studio.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM admin_idempotency_keys WHERE status = 'COMPLETED'")
                .query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(2)
    }

    @Test
    fun `실패 workflow 예약은 요청 trace를 보존해 overview와 관측 링크에서 찾는다`() {
        val actor = adminAccountFixture.관리자("workflow-trace-owner")
        val graph = createGraph(actor.requiredId, "trace")
        val traceId = "0123456789abcdef"

        MDC.put(HttpLoggingFilter.TRACE_ID_KEY, traceId)
        try {
            assertThatThrownBy {
                execute(
                    actor.requiredId,
                    AdminResourceType.GALLERY,
                    graph.gallery.id,
                    AdminWorkflowAction.REOPEN_GALLERY,
                    999,
                    "workflow-trace-failure-001",
                    mapOf("selectionDeadline" to ZonedDateTime.now().plusDays(7).toOffsetDateTime().toString()),
                )
            }.isInstanceOf(AdminException::class.java)
        } finally {
            MDC.remove(HttpLoggingFilter.TRACE_ID_KEY)
        }

        val failed = operationsOverviewService.get().recentFailedOperations
            .single { it.action == "WORKFLOW_REOPEN_GALLERY" }
        assertThat(failed.correlationId).isEqualTo(traceId)
        assertThat(failed.targetType).isEqualTo(AdminResourceType.GALLERY)
        assertThat(failed.targetId).isEqualTo(graph.gallery.id.toString())
        assertThat(observabilityLinkService.links(failed.correlationId!!).correlationId).isEqualTo(traceId)
    }

    @Test
    fun `스튜디오 소유자와 멤버 및 갤러리 공개 상태와 운영 상태와 링크 만료를 분리한다`() {
        val actor = adminAccountFixture.관리자("workflow-relationship-owner")
        val graph = createGraph(actor.requiredId, "relationship")
        val nextOwner = createUser(actor.requiredId, "relationship-next-owner")
        val galleryClient = createUser(actor.requiredId, "relationship-gallery-client")
        val nextOwnerRevisionCountBefore = userRevisionCount(nextOwner.id)
        val nextOwnerAuditCountBefore = userAuditCount(nextOwner.id)
        val galleryClientRevisionCountBefore = userRevisionCount(galleryClient.id)
        val galleryClientAuditCountBefore = userAuditCount(galleryClient.id)

        val addedStudioMember = execute(
            actor.requiredId,
            AdminResourceType.STUDIO,
            graph.studio.id,
            AdminWorkflowAction.ADD_STUDIO_MEMBER,
            0,
            "studio-member-add-001",
            mapOf("userId" to nextOwner.id),
        )
        execute(
            actor.requiredId,
            AdminResourceType.STUDIO,
            graph.studio.id,
            AdminWorkflowAction.SET_STUDIO_OWNER,
            1,
            "studio-owner-set-001",
            mapOf("userId" to nextOwner.id),
        )
        val memberId = (addedStudioMember.details.getValue("memberId") as Number).toLong()

        val roles = jdbcClient.sql(
            "SELECT user_id, role FROM workspace_members WHERE workspace_id = :studioId AND deleted_at IS NULL ORDER BY user_id",
        ).param("studioId", graph.studio.id)
            .query { rs, _ -> rs.getLong("user_id") to rs.getString("role") }.list().toMap()
        assertThat(roles).containsEntry(graph.user.id, "OWNER").containsEntry(nextOwner.id, "OWNER")
        assertThat(memberId).isPositive()

        val galleryMember = execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.ADD_GALLERY_MEMBER,
            0,
            "gallery-member-add-001",
            mapOf("userId" to galleryClient.id),
        )
        execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.REMOVE_GALLERY_MEMBER,
            1,
            "gallery-member-remove-001",
            mapOf("memberId" to (galleryMember.details.getValue("memberId") as Number).toLong()),
        )
        val studioUserRelations = contextService.get(AdminResourceType.STUDIO, graph.studio.id).relations
            .filter { relation -> relation.type == AdminResourceType.USER }
            .map { relation -> relation.id }
        assertThat(studioUserRelations).contains(graph.user.id, nextOwner.id)
        val galleryContextAfterRemoval = contextService.get(AdminResourceType.GALLERY, graph.gallery.id)
        assertThat(
            galleryContextAfterRemoval.relations
                .filter { relation -> relation.type == AdminResourceType.USER }
                .map { relation -> relation.id },
        ).isEmpty()
        assertThat(galleryContextAfterRemoval.facts["members"]).isEqualTo(0L)
        assertThat(userRevisionCount(nextOwner.id)).isEqualTo(nextOwnerRevisionCountBefore)
        assertThat(userAuditCount(nextOwner.id)).isEqualTo(nextOwnerAuditCountBefore)
        assertThat(userRevisionCount(galleryClient.id)).isEqualTo(galleryClientRevisionCountBefore)
        assertThat(userAuditCount(galleryClient.id)).isEqualTo(galleryClientAuditCountBefore)
        execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.UPDATE_GALLERY_STATES,
            2,
            "gallery-states-set-001",
            mapOf(
                "publicStatus" to "CLOSED",
                "workflowStatus" to "IN_PROGRESS",
                "selectionDeadline" to ZonedDateTime.now().plusDays(30).toOffsetDateTime().toString(),
            ),
        )
        val reissueStartedAt = ZonedDateTime.now()
        jdbcClient.sql(
            """
            INSERT INTO gallery_invites
                (gallery_id, token, kind, max_uses, used_count, expires_at, version, created_at, updated_at)
            VALUES
                (:galleryId, 'previous-policy-token', 'STUDIO_MEMBER', 5, 5,
                 CURRENT_TIMESTAMP + INTERVAL '3 days', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("galleryId", graph.gallery.id).update()
        val fullInviteContext = contextService.get(AdminResourceType.GALLERY, graph.gallery.id)
        assertThat(fullInviteContext.facts)
            .containsEntry("stage", "UPLOAD")
            .containsEntry("inviteStatus", "FULL")
        assertThat(fullInviteContext.sections.getValue("invites").single())
            .containsEntry("kind", "STUDIO_MEMBER")
            .containsEntry("maxUses", 5)
            .containsEntry("usedCount", 5)
            .containsEntry("remainingUses", 0)
            .containsEntry("status", "FULL")
        val inviteRequest = AdminWorkflowRequest(
            AdminWorkflowAction.REISSUE_GALLERY_INVITE,
            "만료된 초대 재발급",
            3,
            "gallery-invite-reissue-001",
            true,
        )
        val invite = service.execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            inviteRequest,
            "127.0.0.1",
        )
        val inviteReplay = service.execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            inviteRequest,
            "127.0.0.1",
        )

        assertThat(invite.details["inviteUrl"]?.toString()).contains("/invite/")
        assertThat(invite.details)
            .containsEntry("kind", "STUDIO_MEMBER")
            .containsEntry("maxUses", 5)
            .containsEntry("usedCount", 0)
        assertThat(invite.details["revealed"]).isEqualTo(true)
        val defaultExpiry = invite.details.getValue("expiresAt") as ZonedDateTime
        assertThat(defaultExpiry).isAfter(reissueStartedAt.plusDays(6)).isBefore(reissueStartedAt.plusDays(8))
        assertThat(inviteReplay.replayed).isTrue()
        assertThat(inviteReplay.details).doesNotContainKey("inviteUrl")
        assertThat(inviteReplay.details["revealed"]).isEqualTo(false)
        assertThat(
            jdbcClient.sql(
                """
                SELECT result_payload FROM admin_idempotency_keys
                WHERE action = 'WORKFLOW_REISSUE_GALLERY_INVITE'
                  AND idempotency_key = 'gallery-invite-reissue-001'
                """.trimIndent(),
            ).query { rs, _ -> rs.getString(1) }.single(),
        ).doesNotContain("inviteUrl")
        val galleryStates = jdbcClient.sql(
            "SELECT status, workflow_status FROM galleries WHERE id = :galleryId",
        ).param("galleryId", graph.gallery.id)
            .query { rs, _ -> rs.getString("status") to rs.getString("workflow_status") }.single()
        assertThat(galleryStates).isEqualTo("CLOSED" to "IN_PROGRESS")
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM gallery_invites WHERE gallery_id = :galleryId AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP",
            ).param("galleryId", graph.gallery.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        assertThat(
            jdbcClient.sql(
                "SELECT kind, max_uses, used_count FROM gallery_invites WHERE id = :inviteId",
            ).param("inviteId", (invite.details.getValue("inviteId") as Number).toLong())
                .query { rs, _ -> listOf(rs.getString("kind"), rs.getInt("max_uses"), rs.getInt("used_count")) }
                .single(),
        ).containsExactly("STUDIO_MEMBER", 5, 0)

        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.GALLERY,
                graph.gallery.id,
                AdminWorkflowAction.REISSUE_GALLERY_INVITE,
                4,
                "gallery-invite-invalid-kind-001",
                mapOf("kind" to "PERSONAL_PARTNER"),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.GALLERY,
                graph.gallery.id,
                AdminWorkflowAction.REISSUE_GALLERY_INVITE,
                4,
                "gallery-invite-expired-001",
                mapOf("expiresAt" to ZonedDateTime.now().minusMinutes(1).toOffsetDateTime().toString()),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val explicitExpiry = ZonedDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MICROS).toOffsetDateTime()
        val explicitInvite = execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.REISSUE_GALLERY_INVITE,
            4,
            "gallery-invite-explicit-001",
            mapOf(
                "kind" to "GALLERY_MEMBER",
                "maxUses" to 3,
                "expiresAt" to explicitExpiry.toString(),
            ),
        )
        assertThat(explicitInvite.details)
            .containsEntry("kind", "GALLERY_MEMBER")
            .containsEntry("maxUses", 3)
            .containsEntry("usedCount", 0)
        assertThat((explicitInvite.details.getValue("expiresAt") as ZonedDateTime).toInstant())
            .isEqualTo(explicitExpiry.toInstant())

        val collaboration = resourceService.create(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            CreateAdminResourceRequest(
                "협업 링크 생성",
                mapOf(
                    "galleryId" to graph.gallery.id,
                    "conceptFolderId" to createConceptFolder(graph.gallery.id, "가족 협업"),
                    "name" to "가족 협업",
                ),
            ),
            "127.0.0.1",
        )
        val collabLink = execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.REISSUE_COLLAB_LINK,
            0,
            "collab-link-reissue-001",
            mapOf("ttlSeconds" to 600),
        )
        assertThat(collabLink.details["collabUrl"]?.toString()).contains("/collab/")
        assertThat(collabLink.details["revealed"]).isEqualTo(true)
        assertThat(
            jdbcClient.sql(
                "SELECT expires_at > CURRENT_TIMESTAMP AND revoked_at IS NULL FROM collab_sessions WHERE id = :id",
            ).param("id", collaboration.id).query { rs, _ -> rs.getBoolean(1) }.single(),
        ).isTrue()
        val photoId = createPhoto(actor.requiredId, graph.gallery.id, "collab-content").id
        assignPhotoToSession(collaboration.id, photoId)
        val guestId = jdbcClient.sql(
            """
            INSERT INTO collab_guests (collab_session_id, guest_token, nickname, version, created_at, updated_at)
            VALUES (:sessionId, 'guest-token', '하객', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", collaboration.id).query { rs, _ -> rs.getLong(1) }.single()
        val comment = execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.CREATE_COLLAB_COMMENT,
            1,
            "collab-comment-create-001",
            mapOf("photoId" to photoId, "guestId" to guestId, "content" to "첫 의견"),
        )
        val commentId = (comment.details.getValue("commentId") as Number).toLong()
        execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.UPDATE_COLLAB_COMMENT,
            2,
            "collab-comment-update-001",
            mapOf("commentId" to commentId, "commentExpectedVersion" to 0, "content" to "수정된 의견"),
        )
        execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.DELETE_COLLAB_COMMENT,
            3,
            "collab-comment-delete-001",
            mapOf("commentId" to commentId, "commentExpectedVersion" to 1),
        )
        val restoredComment = execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.RESTORE_COLLAB_COMMENT,
            4,
            "collab-comment-restore-001",
            mapOf("commentId" to commentId, "commentExpectedVersion" to 2),
        )
        assertThat(restoredComment.details["trashStatus"]).isEqualTo("RESTORED")
        assertThat(restoredComment.details["restoreWindowDays"]).isEqualTo(7L)
        val addedLike = execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.ADD_COLLAB_LIKE,
            5,
            "collab-like-add-001",
            mapOf("photoId" to photoId, "guestId" to guestId),
        )
        val likeId = (addedLike.details.getValue("likeId") as Number).toLong()
        val removedLike = execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.REMOVE_COLLAB_LIKE,
            6,
            "collab-like-remove-001",
            mapOf("photoId" to photoId, "guestId" to guestId, "likeExpectedVersion" to 0),
        )
        assertThat(removedLike.details["trashStatus"]).isEqualTo("ACTIVE")
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM collab_photo_likes WHERE id = :likeId AND deleted_at IS NULL",
            ).param("likeId", likeId).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
        val restoredLike = execute(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            AdminWorkflowAction.RESTORE_COLLAB_LIKE,
            7,
            "collab-like-restore-001",
            mapOf("likeId" to likeId, "likeExpectedVersion" to 1),
        )
        assertThat(restoredLike.details["trashStatus"]).isEqualTo("RESTORED")
        assertThat(
            jdbcClient.sql("SELECT content FROM collab_photo_comments WHERE id = :id AND deleted_at IS NULL")
                .param("id", commentId).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("수정된 의견")
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM collab_photo_likes WHERE photo_id = :photoId AND deleted_at IS NULL",
            )
                .param("photoId", photoId).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_child_trash_records WHERE resource_type = 'COLLAB_COMMENT' AND status = 'RESTORED'",
            ).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_child_trash_records WHERE resource_type = 'COLLAB_LIKE' AND status = 'RESTORED'",
            ).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        @Suppress("UNCHECKED_CAST")
        val collaborationContext = contextService.get(AdminResourceType.COLLABORATION, collaboration.id).sections
        val sharedPhoto = (collaborationContext.getValue("sharedPhotos") as List<Map<String, Any?>>).single()
        val contextComment = (collaborationContext.getValue("comments") as List<Map<String, Any?>>).single()
        val contextLike = (collaborationContext.getValue("likes") as List<Map<String, Any?>>).single()
        assertThat(sharedPhoto["photoId"]).isEqualTo(photoId)
        assertThat(contextComment["version"]).isEqualTo(3L)
        assertThat(contextLike["photoId"]).isEqualTo(photoId)
        assertThat(contextLike["version"]).isEqualTo(2L)
        val studioContext = contextService.get(AdminResourceType.STUDIO, graph.studio.id)
        assertThat(studioContext.facts["ownerId"]).isEqualTo(graph.user.id)
    }

    @Test
    fun `스튜디오는 기존 소유자를 유지한 채 공동 소유자를 추가한다`() {
        val actor = adminAccountFixture.관리자("workflow-studio-owner-policy")
        val graph = createGraph(actor.requiredId, "studio-owner-policy")
        val nextOwner = createUser(actor.requiredId, "studio-next-owner")
        val revisionCountBefore = userRevisionCount(nextOwner.id)
        val auditCountBefore = userAuditCount(nextOwner.id)

        execute(
            actor.requiredId,
            AdminResourceType.STUDIO,
            graph.studio.id,
            AdminWorkflowAction.SET_STUDIO_OWNER,
            graph.studio.version,
            "studio-owner-policy-set-001",
            mapOf("userId" to nextOwner.id),
        )

        val owners = jdbcClient.sql(
            "SELECT user_id FROM workspace_members WHERE workspace_id = :workspaceId AND role = 'OWNER' AND deleted_at IS NULL ORDER BY user_id",
        ).param("workspaceId", graph.studio.id).query { rs, _ -> rs.getLong(1) }.list()
        assertThat(owners).containsExactlyInAnyOrder(graph.user.id, nextOwner.id)
        assertThat(resourceService.get(AdminResourceType.USER, nextOwner.id).version).isEqualTo(nextOwner.version)
        assertThat(userRevisionCount(nextOwner.id)).isEqualTo(revisionCountBefore)
        assertThat(userAuditCount(nextOwner.id)).isEqualTo(auditCountBefore)
        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.STUDIO, graph.studio.id,
                AdminWorkflowAction.SET_STUDIO_OWNER, 1, "studio-owner-policy-duplicate-001",
                mapOf("userId" to nextOwner.id),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    @Test
    fun `갤러리 초대 멤버는 최대 둘이고 현재 워크스페이스 구성원만 거절한다`() {
        val actor = adminAccountFixture.관리자("workflow-gallery-member-policy")
        val graph = createGraph(actor.requiredId, "gallery-member-policy")
        val firstClient = createUser(actor.requiredId, "gallery-member-first")
        val secondClient = createUser(actor.requiredId, "gallery-member-second")
        val thirdClient = createUser(actor.requiredId, "gallery-member-third")

        val first = execute(
            actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
            AdminWorkflowAction.ADD_GALLERY_MEMBER, 0, "gallery-policy-first-001",
            mapOf("userId" to firstClient.id),
        )
        execute(
            actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
            AdminWorkflowAction.ADD_GALLERY_MEMBER, 1, "gallery-policy-second-001",
            mapOf("userId" to secondClient.id),
        )
        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
                AdminWorkflowAction.ADD_GALLERY_MEMBER, 2, "gallery-policy-third-001",
                mapOf("userId" to thirdClient.id),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        assertThat(resourceService.get(AdminResourceType.USER, thirdClient.id).version).isEqualTo(thirdClient.version)

        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
                AdminWorkflowAction.ADD_GALLERY_MEMBER, 2, "gallery-policy-owner-001",
                mapOf("userId" to graph.user.id),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val photographerWedding = createGraph(actor.requiredId, "gallery-member-photographer-wedding")
        val photographerRevisionCountBefore = userRevisionCount(graph.user.id)
        val photographerAuditCountBefore = userAuditCount(graph.user.id)
        val admittedPhotographer = execute(
            actor.requiredId, AdminResourceType.GALLERY, photographerWedding.gallery.id,
            AdminWorkflowAction.ADD_GALLERY_MEMBER, 0, "gallery-policy-photographer-allow-001",
            mapOf("userId" to graph.user.id),
        )
        assertThat((admittedPhotographer.details.getValue("memberId") as Number).toLong()).isPositive()
        assertThat(resourceService.get(AdminResourceType.USER, graph.user.id).version).isEqualTo(graph.user.version)
        assertThat(userRevisionCount(graph.user.id)).isEqualTo(photographerRevisionCountBefore)
        assertThat(userAuditCount(graph.user.id)).isEqualTo(photographerAuditCountBefore)

        val staff = createUser(actor.requiredId, "gallery-member-staff")
        execute(
            actor.requiredId, AdminResourceType.STUDIO, graph.studio.id,
            AdminWorkflowAction.ADD_STUDIO_MEMBER, 0, "gallery-policy-staff-add-001",
            mapOf("userId" to staff.id),
        )
        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
                AdminWorkflowAction.ADD_GALLERY_MEMBER, 2, "gallery-policy-staff-reject-001",
                mapOf("userId" to staff.id),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val memberId = (first.details.getValue("memberId") as Number).toLong()
        execute(
            actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
            AdminWorkflowAction.REMOVE_GALLERY_MEMBER, 2, "gallery-policy-remove-001",
            mapOf("memberId" to memberId),
        )
        val restored = execute(
            actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
            AdminWorkflowAction.ADD_GALLERY_MEMBER, 3, "gallery-policy-restore-001",
            mapOf("userId" to firstClient.id),
        )
        assertThat(restored.details["memberId"]).isEqualTo(memberId)
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM gallery_members WHERE gallery_id = :galleryId AND deleted_at IS NULL",
            ).param("galleryId", graph.gallery.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(2)
        assertThat(resourceService.get(AdminResourceType.USER, firstClient.id).version).isEqualTo(firstClient.version)
        assertThat(resourceService.get(AdminResourceType.USER, secondClient.id).version).isEqualTo(secondClient.version)
    }

    @Test
    fun `갤러리 상태 workflow는 과거 마감을 거절하고 재오픈 때 미래 마감을 반드시 갱신한다`() {
        val actor = adminAccountFixture.관리자("workflow-gallery-deadline-policy")
        val graph = createGraph(actor.requiredId, "gallery-deadline-policy")
        val pastDeadline = ZonedDateTime.now().minusMinutes(1).toOffsetDateTime().toString()

        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
                AdminWorkflowAction.UPDATE_GALLERY_STATES, 0, "gallery-deadline-past-state-001",
                mapOf(
                    "publicStatus" to "OPEN",
                    "workflowStatus" to "IN_PROGRESS",
                    "selectionDeadline" to pastDeadline,
                ),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
                AdminWorkflowAction.REOPEN_GALLERY, 0, "gallery-reopen-missing-deadline-001",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val initialFuture = ZonedDateTime.now().plusDays(7).truncatedTo(ChronoUnit.MICROS).toOffsetDateTime()
        execute(
            actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
            AdminWorkflowAction.UPDATE_GALLERY_STATES, 0, "gallery-deadline-close-001",
            mapOf(
                "publicStatus" to "CLOSED",
                "workflowStatus" to "COMPLETED",
                "selectionDeadline" to initialFuture.toString(),
            ),
        )
        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
                AdminWorkflowAction.REOPEN_GALLERY, 1, "gallery-reopen-past-deadline-001",
                mapOf("selectionDeadline" to pastDeadline),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val renewedDeadline = ZonedDateTime.now().plusDays(30).truncatedTo(ChronoUnit.MICROS).toOffsetDateTime()
        val reopened = execute(
            actor.requiredId, AdminResourceType.GALLERY, graph.gallery.id,
            AdminWorkflowAction.REOPEN_GALLERY, 1, "gallery-reopen-future-deadline-001",
            mapOf("selectionDeadline" to renewedDeadline.toString()),
        )
        assertThat(reopened.details["selectionDeadline"]).isEqualTo(renewedDeadline)
        val state = jdbcClient.sql(
            "SELECT status, workflow_status, selection_deadline FROM galleries WHERE id = :galleryId",
        ).param("galleryId", graph.gallery.id)
            .query { rs, _ -> Triple(
                rs.getString("status"),
                rs.getString("workflow_status"),
                rs.getObject("selection_deadline", java.time.OffsetDateTime::class.java),
            ) }.single()
        assertThat(state.first).isEqualTo("OPEN")
        assertThat(state.second).isEqualTo("IN_PROGRESS")
        assertThat(state.third.toInstant()).isEqualTo(renewedDeadline.toInstant())
        assertThat(reopened.details["stage"]).isEqualTo("SELECTION_IN_PROGRESS")
        assertThat(resourceService.get(AdminResourceType.GALLERY, graph.gallery.id).fields["stage"])
            .isEqualTo("SELECTION_IN_PROGRESS")
    }

    @Test
    fun `휴지통 좋아요 뒤 하객이 다시 누르면 새 반응을 보존하고 예전 행 복원을 거절한다`() {
        val actor = adminAccountFixture.관리자("workflow-like-conflict-owner")
        val graph = createGraph(actor.requiredId, "like-conflict", withPhoto = true)
        val collaboration = resourceService.create(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            CreateAdminResourceRequest(
                "좋아요 충돌 협업",
                mapOf(
                    "galleryId" to graph.gallery.id,
                    "conceptFolderId" to createConceptFolder(graph.gallery.id, "좋아요 충돌"),
                    "name" to "가족",
                ),
            ),
            "127.0.0.1",
        )
        val photoId = requireNotNull(graph.photo).id
        assignPhotoToSession(collaboration.id, photoId)
        val guestId = jdbcClient.sql(
            """
            INSERT INTO collab_guests (collab_session_id, guest_token, nickname, version, created_at, updated_at)
            VALUES (:sessionId, 'like-conflict-guest', '하객', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
            """.trimIndent(),
        ).param("sessionId", collaboration.id).query { rs, _ -> rs.getLong(1) }.single()
        val added = execute(
            actor.requiredId, AdminResourceType.COLLABORATION, collaboration.id,
            AdminWorkflowAction.ADD_COLLAB_LIKE, 0, "collab-like-conflict-add-001",
            mapOf("photoId" to photoId, "guestId" to guestId),
        )
        val likeId = (added.details.getValue("likeId") as Number).toLong()
        execute(
            actor.requiredId, AdminResourceType.COLLABORATION, collaboration.id,
            AdminWorkflowAction.REMOVE_COLLAB_LIKE, 1, "collab-like-conflict-remove-001",
            mapOf("photoId" to photoId, "guestId" to guestId, "likeExpectedVersion" to 0),
        )
        jdbcClient.sql(
            """
            INSERT INTO collab_photo_likes
                (collab_session_id, photo_id, collab_guest_id, version, created_at, updated_at)
            VALUES (:sessionId, :photoId, :guestId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("sessionId", collaboration.id).param("photoId", photoId)
            .param("guestId", guestId).update()

        assertThatThrownBy {
            execute(
                actor.requiredId, AdminResourceType.COLLABORATION, collaboration.id,
                AdminWorkflowAction.RESTORE_COLLAB_LIKE, 2, "collab-like-conflict-restore-001",
                mapOf("likeId" to likeId, "likeExpectedVersion" to 1),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM collab_photo_likes WHERE photo_id=:photoId AND deleted_at IS NULL",
            ).param("photoId", photoId).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        assertThat(
            jdbcClient.sql("SELECT deleted_at IS NOT NULL FROM collab_photo_likes WHERE id=:id")
                .param("id", likeId).query { rs, _ -> rs.getBoolean(1) }.single(),
        ).isTrue()
    }

    @Test
    fun `원본 교체는 트랜잭션 밖 S3 HEAD 뒤 리비전과 실제 대기 작업만 한 번 만든다`() {
        val actor = adminAccountFixture.관리자("workflow-photo-owner")
        val graph = createGraph(actor.requiredId, "photo", withPhoto = true)
        val photo = requireNotNull(graph.photo)
        val replacementKey = "galleries/${graph.gallery.id}/replacement.jpg"
        jdbcClient.sql(
            """
            UPDATE photos
            SET exposure_time='1/200', f_number=2.8, iso=400,
                technical_quality_score=91.5,
                technical_quality_signals='{"algorithmVersion":"old"}'::JSONB,
                quality_analyzed_at=CURRENT_TIMESTAMP
            WHERE id=:photoId
            """.trimIndent(),
        ).param("photoId", photo.id).update()
        var presignCount = 0
        whenever(photoStorage.buildKey(graph.gallery.id, "replacement.jpg")).thenAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            replacementKey
        }
        whenever(photoStorage.presignUpload(replacementKey, "image/jpeg")).thenAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            presignCount++
            PresignedUploadDto(
                "https://upload.example.test/object?X-Amz-Signature=secret-signature-$presignCount",
                Instant.now().plusSeconds(1800),
            )
        }
        whenever(photoStorage.exists(replacementKey)).thenAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            true
        }

        val issueRequest = AdminWorkflowRequest(
            action = AdminWorkflowAction.ISSUE_PHOTO_REPLACEMENT,
            reason = "[CUSTOMER_REQUEST] 고객 private@example.com 010-1234-5678 원본 교체",
            expectedVersion = 0,
            idempotencyKey = "photo-replacement-issue-001",
            confirm = true,
            fields = mapOf("fileName" to "replacement.jpg", "contentType" to "image/jpeg"),
        )
        val issued = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            issueRequest,
            "127.0.0.1",
        )
        val issueReplay = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            issueRequest,
            "127.0.0.1",
        )
        assertThat(issued.details["uploadUrl"]?.toString()).contains("secret-signature-1")
        assertThat(issued.details["revealed"]).isEqualTo(true)
        assertThat(issueReplay.replayed).isTrue()
        assertThat(issueReplay.details["uploadUrl"]?.toString()).contains("secret-signature-2")
        assertThat(issueReplay.details["uploadUrl"]).isNotEqualTo(issued.details["uploadUrl"])
        assertThat(issueReplay.details["revealed"]).isEqualTo(true)
        assertThat(issueReplay.details["reissued"]).isEqualTo(true)
        assertThat(
            jdbcClient.sql(
                """
                SELECT result_payload FROM admin_idempotency_keys
                WHERE action = 'WORKFLOW_ISSUE_PHOTO_REPLACEMENT'
                  AND idempotency_key = 'photo-replacement-issue-001'
                """.trimIndent(),
            ).query { rs, _ -> rs.getString(1) }.single(),
        ).doesNotContain("uploadUrl", "secret-signature")
        val replacementId = (issued.details.getValue("replacementId") as Number).toLong()
        assertThat(
            jdbcClient.sql(
                "SELECT storage_key || '|' || content_type FROM admin_photo_replacement_uploads WHERE id=:id",
            ).param("id", replacementId).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("$replacementKey|image/jpeg").doesNotContain("http", "X-Amz")
        val completeRequest = AdminWorkflowRequest(
            AdminWorkflowAction.COMPLETE_PHOTO_REPLACEMENT,
            "[DATA_CORRECTION] 고객 pii@example.com 원본 반영",
            1,
            "photo-replacement-complete-001",
            true,
            mapOf("replacementId" to replacementId),
        )
        val completed = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            completeRequest,
            "127.0.0.1",
        )
        val replay = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            completeRequest,
            "127.0.0.1",
        )

        assertThat(completed.details["processingStatus"]).isEqualTo("PENDING")
        assertThat(completed.details["processingJobIds"] as List<*>).hasSize(3)
        assertThat(replay.replayed).isTrue()
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM admin_photo_revisions WHERE photo_id = :photoId")
                .param("photoId", photo.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(2)
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_processing_jobs WHERE target_type = 'PHOTO' AND target_id = :photoId AND status = 'PENDING'",
            ).param("photoId", photo.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(3)
        assertThat(
            jdbcClient.sql("SELECT storage_key FROM photos WHERE id = :photoId")
                .param("photoId", photo.id).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo(replacementKey)
        assertThat(
            jdbcClient.sql(
                """
                SELECT technical_quality_score IS NULL
                       AND technical_quality_signals IS NULL
                       AND quality_analyzed_at IS NULL
                       AND exposure_time IS NULL
                       AND f_number IS NULL
                       AND iso IS NULL
                FROM photos WHERE id=:photoId
                """.trimIndent(),
            ).param("photoId", photo.id).query { rs, _ -> rs.getBoolean(1) }.single(),
        ).isTrue()
        val jobIds = (completed.details.getValue("processingJobIds") as List<*>)
            .map { (it as Number).toLong() }
        val retryRequest = AdminWorkflowRequest(
            AdminWorkflowAction.RETRY_PROCESSING_JOB,
            "임베딩 처리 재시도",
            2,
            "photo-processing-retry-001",
            true,
            mapOf("jobId" to jobIds[1]),
        )
        val retried = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            retryRequest,
            "127.0.0.1",
        )
        val retryReplay = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            retryRequest,
            "127.0.0.1",
        )
        assertThat(retried.status).isEqualTo("PENDING")
        assertThat(retryReplay.replayed).isTrue()
        assertThat(
            jdbcClient.sql(
                """
                SELECT action || ':' || outcome
                FROM admin_audit_logs
                WHERE target_type = 'PHOTO' AND target_id = :targetId
                  AND action IN ('REPROCESS_REQUESTED', 'REPROCESS_DISPATCHED', 'REPROCESS_DISPATCH_FAILED')
                ORDER BY id
                """.trimIndent(),
            ).param("targetId", photo.id.toString()).query { rs, _ -> rs.getString(1) }.list(),
        ).containsExactly("REPROCESS_REQUESTED:SUCCESS")
        execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            AdminWorkflowAction.CANCEL_PROCESSING_JOB,
            3,
            "photo-processing-cancel-001",
            mapOf("jobId" to jobIds[0]),
        )
        val notificationRequest = AdminWorkflowRequest(
            AdminWorkflowAction.RESEND_NOTIFICATION,
            "[CUSTOMER_REQUEST] 고객 notify@example.com 알림 재전송",
            4,
            "photo-notification-resend-001",
            true,
            mapOf(
                "notificationType" to "PHOTO_REPLACED",
            ),
        )
        service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            notificationRequest,
            "127.0.0.1",
        )
        val notificationReplay = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            notificationRequest,
            "127.0.0.1",
        )
        assertThat(notificationReplay.replayed).isTrue()
        assertThat(
            jdbcClient.sql("SELECT status FROM admin_processing_jobs WHERE id = :id")
                .param("id", jobIds[1]).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("PENDING")
        assertThat(
            jdbcClient.sql("SELECT status FROM admin_processing_jobs WHERE id = :id")
                .param("id", jobIds[0]).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("CANCELED")
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM admin_notification_inbox WHERE target_id = :photoId")
                .param("photoId", photo.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM admin_notification_outbox WHERE source_id = :photoId")
                .param("photoId", photo.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
        val durableReasons = jdbcClient.sql(
            """
            SELECT reason FROM admin_photo_replacement_uploads WHERE photo_id = :photoId
            UNION ALL
            SELECT reason FROM admin_processing_jobs WHERE target_type = 'PHOTO' AND target_id = :photoId
            """.trimIndent(),
        ).param("photoId", photo.id).query { rs, _ -> rs.getString(1) }.list()
        assertThat(durableReasons)
            .contains("reasonCategory=CUSTOMER_REQUEST operatorReasonProvided=true")
            .contains("reasonCategory=DATA_CORRECTION operatorReasonProvided=true")
        assertThat(durableReasons.joinToString())
            .doesNotContain("private@example.com", "pii@example.com", "notify@example.com", "010-1234-5678")
        val auditSnapshots = jdbcClient.sql(
            "SELECT COALESCE(before_snapshot, '') || COALESCE(after_snapshot, '') FROM admin_entity_revisions",
        ).query { rs, _ -> rs.getString(1) }.list().joinToString()
        assertThat(auditSnapshots).doesNotContain("secret-signature")
        assertThat(contextService.get(AdminResourceType.PHOTO, photo.id).toString())
            .doesNotContain("secret-signature", "upload.example.test")
        val persistedAudit = jdbcClient.sql(
            """
            SELECT COALESCE(changed_fields, '') AS evidence FROM admin_audit_logs
            UNION ALL
            SELECT COALESCE(before_snapshot, '') || COALESCE(after_snapshot, '') AS evidence
            FROM admin_entity_revisions
            """.trimIndent(),
        ).query { rs, _ -> rs.getString("evidence") }.list().joinToString()
        assertThat(persistedAudit).doesNotContain("secret-signature", "upload.example.test")
        verify(photoStorage, times(2)).presignUpload(replacementKey, "image/jpeg")
        verify(photoStorage).exists(replacementKey)
        verify(embeddingInvoker, never()).invoke(any(), any())
    }

    @Test
    fun `재처리 요청은 미전송 claim의 attempt를 소비하지 않고 PENDING으로 재등록하며 멱등 재생한다`() {
        val actor = adminAccountFixture.관리자("workflow-reprocess-manual-requeue")
        val graph = createGraph(actor.requiredId, "reprocess-manual-requeue", withPhoto = true)
        val photo = requireNotNull(graph.photo)
        val jobId = createEmbeddingJob(actor.requiredId, photo.id, graph.gallery.id)
        jdbcClient.sql(
            """
            UPDATE admin_processing_jobs
            SET status = 'DISPATCHING', attempt_count = 3, failure_code = 'CLAIMED_NOT_SENT',
                last_run_at = CURRENT_TIMESTAMP,
                actor_admin_id = NULL, reason = 'reasonCategory=UNSPECIFIED operatorReasonProvided=false'
            WHERE id = :id
            """.trimIndent(),
        ).param("id", jobId).update()

        val request = AdminWorkflowRequest(
            action = AdminWorkflowAction.RETRY_PROCESSING_JOB,
            reason = "[DATA_CORRECTION] 수동 재시도",
            expectedVersion = photo.version,
            idempotencyKey = "reprocess-manual-requeue-001",
            confirm = true,
            fields = mapOf("jobId" to jobId),
        )
        val first = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            request,
            "127.0.0.1",
        )
        val replay = service.execute(
            actor.requiredId,
            AdminResourceType.PHOTO,
            photo.id,
            request,
            "127.0.0.1",
        )

        assertThat(first.status).isEqualTo("PENDING")
        assertThat(first.details["attemptCount"]).isEqualTo(0)
        assertThat(replay.replayed).isTrue()
        assertThat(
            jdbcClient.sql(
                """
                SELECT status || ':' || attempt_count || ':' || COALESCE(failure_code, 'NULL') || ':' ||
                       actor_admin_id || ':' || reason
                FROM admin_processing_jobs WHERE id = :id
                """.trimIndent(),
            ).param("id", jobId).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo(
            "PENDING:0:NULL:${actor.requiredId}:" +
                "reasonCategory=DATA_CORRECTION operatorReasonProvided=true",
        )
        assertThat(resourceService.get(AdminResourceType.PHOTO, photo.id).version).isEqualTo(photo.version + 1)
        assertThat(
            jdbcClient.sql(
                """
                SELECT action || ':' || outcome
                FROM admin_audit_logs
                WHERE target_type = 'PHOTO' AND target_id = :targetId
                  AND action IN (
                    'REPROCESS_REQUESTED', 'REPROCESS_DISPATCHED',
                    'REPROCESS_DISPATCH_FAILED', 'REPROCESS_DISPATCH_UNKNOWN'
                  )
                ORDER BY id
                """.trimIndent(),
            ).param("targetId", photo.id.toString()).query { rs, _ -> rs.getString(1) }.list(),
        ).containsExactly("REPROCESS_REQUESTED:SUCCESS")
        assertThat(
            jdbcClient.sql(
                """
                SELECT status FROM admin_idempotency_keys
                WHERE action = 'WORKFLOW_RETRY_PROCESSING_JOB'
                  AND idempotency_key = 'reprocess-manual-requeue-001'
                """.trimIndent(),
            ).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("COMPLETED")
        verify(embeddingInvoker, never()).invoke(any(), any())
    }

    @Test
    fun `재처리 요청 감사 저장 실패는 job과 리소스 버전을 함께 rollback하고 외부 호출을 막는다`() {
        val actor = adminAccountFixture.관리자("workflow-reprocess-audit-failure")
        val graph = createGraph(actor.requiredId, "reprocess-audit-failure", withPhoto = true)
        val photo = requireNotNull(graph.photo)
        val jobId = createEmbeddingJob(actor.requiredId, photo.id, graph.gallery.id)
        val triggerFunction = "test_block_reprocess_audit_insert"
        val triggerName = "test_block_reprocess_audit_insert_trigger"

        jdbcClient.sql(
            """
            CREATE OR REPLACE FUNCTION $triggerFunction()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS ${'$'}trigger${'$'}
            BEGIN
                IF NEW.action = 'REPROCESS_REQUESTED'
                   AND NEW.target_type = 'PHOTO'
                   AND NEW.target_id = '${photo.id}' THEN
                    RAISE EXCEPTION 'forced reprocess audit failure';
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
            FOR EACH ROW EXECUTE FUNCTION $triggerFunction()
            """.trimIndent(),
        ).update()
        try {
            assertThatThrownBy {
                execute(
                    actor.requiredId,
                    AdminResourceType.PHOTO,
                    photo.id,
                    AdminWorkflowAction.RETRY_PROCESSING_JOB,
                    photo.version,
                    "reprocess-audit-failure-001",
                    mapOf("jobId" to jobId),
                )
            }.isInstanceOf(DataAccessException::class.java)
        } finally {
            jdbcClient.sql("DROP TRIGGER IF EXISTS $triggerName ON admin_audit_logs").update()
            jdbcClient.sql("DROP FUNCTION IF EXISTS $triggerFunction()").update()
        }

        assertThat(
            jdbcClient.sql(
                "SELECT status || ':' || attempt_count FROM admin_processing_jobs WHERE id = :id",
            ).param("id", jobId).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("PENDING:0")
        assertThat(resourceService.get(AdminResourceType.PHOTO, photo.id).version).isEqualTo(photo.version)
        assertThat(
            jdbcClient.sql(
                """
                SELECT COUNT(*) FROM admin_audit_logs
                WHERE target_type = 'PHOTO' AND target_id = :targetId
                  AND action IN ('REPROCESS_REQUESTED', 'REPROCESS_DISPATCHED', 'REPROCESS_DISPATCH_FAILED')
                """.trimIndent(),
            ).param("targetId", photo.id.toString()).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
        assertThat(
            jdbcClient.sql(
                """
                SELECT COUNT(*) FROM admin_idempotency_keys
                WHERE action = 'WORKFLOW_RETRY_PROCESSING_JOB'
                  AND idempotency_key = 'reprocess-audit-failure-001'
                """.trimIndent(),
            ).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
        verify(embeddingInvoker, never()).invoke(any(), any())
    }

    @Test
    fun `AI 초안은 임베딩과 품질로 결정적으로 다양화하고 제출 리비전과 mock 작업을 남긴다`() {
        val actor = adminAccountFixture.관리자("workflow-selection-owner")
        val graph = createGraph(actor.requiredId, "selection")
        val selection = resourceService.create(
            actor.requiredId,
            AdminResourceType.SELECTION,
            CreateAdminResourceRequest("AI 선택 생성", mapOf("galleryId" to graph.gallery.id)),
            "127.0.0.1",
        )
        val photos = (1..3).map { index -> createPhoto(actor.requiredId, graph.gallery.id, "selection-$index") }
        setEmbedded(photos[0].id, unitVector(0), 2400, 1600)
        setEmbedded(photos[1].id, nearVector(0, 1), 2400, 1600)
        setEmbedded(photos[2].id, unitVector(1), 2400, 1600)

        val draft = execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            selection.id,
            AdminWorkflowAction.CREATE_AI_SELECTION_DRAFT,
            0,
            "selection-ai-draft-001",
            mapOf("requestedCount" to 2),
        )
        val revisionId = (draft.details.getValue("revisionId") as Number).toLong()
        assertThat(draft.status).isEqualTo("SUCCEEDED")
        assertThat(draft.details["photoIds"] as List<*>).containsExactly(photos[0].id, photos[2].id)
        assertThatThrownBy {
            jdbcClient.sql("UPDATE admin_selection_revisions SET reason = 'tampered' WHERE id = :id")
                .param("id", revisionId).update()
        }.isInstanceOf(DataAccessException::class.java)

        val submitted = execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            selection.id,
            AdminWorkflowAction.SUBMIT_SELECTION_REVISION,
            1,
            "selection-submit-001",
            mapOf("revisionId" to revisionId),
        )
        val mockJobId = (submitted.details.getValue("mockRecalculationJobId") as Number).toLong()
        assertThat(submitted.status).isEqualTo("PENDING")
        assertThat(
            jdbcClient.sql("SELECT status FROM admin_processing_jobs WHERE id = :id")
                .param("id", mockJobId).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("PENDING")
        assertThat(
            jdbcClient.sql("SELECT status FROM photo_selections WHERE id = :id")
                .param("id", selection.id).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("SUBMITTED")
        val gallerySubmitted = execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.SUBMIT_GALLERY,
            0,
            "gallery-submit-001",
        )
        assertThat(gallerySubmitted.details["stage"]).isEqualTo("SELECTION_COMPLETED")
        val galleryCompleted = execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.COMPLETE_GALLERY,
            1,
            "gallery-complete-001",
        )
        assertThat(galleryCompleted.details["stage"]).isEqualTo("ALBUM")
        val galleryReopened = execute(
            actor.requiredId,
            AdminResourceType.GALLERY,
            graph.gallery.id,
            AdminWorkflowAction.REOPEN_GALLERY,
            2,
            "gallery-reopen-001",
            mapOf("selectionDeadline" to ZonedDateTime.now().plusDays(30).toOffsetDateTime().toString()),
        )
        assertThat(galleryReopened.details["stage"]).isEqualTo("SELECTION_IN_PROGRESS")
        val reopened = jdbcClient.sql("SELECT status, workflow_status FROM galleries WHERE id = :id")
            .param("id", graph.gallery.id)
            .query { rs, _ -> rs.getString("status") to rs.getString("workflow_status") }.single()
        assertThat(reopened).isEqualTo("OPEN" to "IN_PROGRESS")
        assertThat(resourceService.get(AdminResourceType.GALLERY, graph.gallery.id).fields["stage"])
            .isEqualTo("SELECTION_IN_PROGRESS")

        val emptyGraph = createGraph(actor.requiredId, "selection-empty")
        val emptySelection = resourceService.create(
            actor.requiredId,
            AdminResourceType.SELECTION,
            CreateAdminResourceRequest("후보 없는 AI 선택", mapOf("galleryId" to emptyGraph.gallery.id)),
            "127.0.0.1",
        )
        val failed = execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            emptySelection.id,
            AdminWorkflowAction.CREATE_AI_SELECTION_DRAFT,
            0,
            "selection-ai-empty-001",
            mapOf("requestedCount" to 2),
        )
        val failedJobId = (failed.details.getValue("jobId") as Number).toLong()
        assertThat(failed.status).isEqualTo("FAILED")
        assertThat(failed.details["failureCode"]).isEqualTo("NO_EMBEDDED_CANDIDATES")
        assertThat(failed.details["revisionId"]).isNull()
        execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            emptySelection.id,
            AdminWorkflowAction.CANCEL_AI_SELECTION_JOB,
            1,
            "selection-ai-cancel-001",
            mapOf("jobId" to failedJobId),
        )
        val retryFailed = execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            emptySelection.id,
            AdminWorkflowAction.RETRY_AI_SELECTION_JOB,
            2,
            "selection-ai-retry-001",
            mapOf("jobId" to failedJobId, "requestedCount" to 2),
        )
        assertThat(retryFailed.status).isEqualTo("FAILED")
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_ai_selection_jobs WHERE selection_id = :selectionId AND status = 'SUCCEEDED'",
            ).param("selectionId", emptySelection.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
        verify(embeddingInvoker, never()).invoke(any(), any())
    }

    @Test
    fun `AI 초안 품질은 분석 점수를 우선 쓰고 미분석 사진은 별점과 해상도로 계산한다`() {
        val actor = adminAccountFixture.관리자("workflow-quality-selection-owner")
        val graph = createGraph(actor.requiredId, "quality-selection")
        val selection = resourceService.create(
            actor.requiredId,
            AdminResourceType.SELECTION,
            CreateAdminResourceRequest("기술 품질 선택 생성", mapOf("galleryId" to graph.gallery.id)),
            "127.0.0.1",
        )
        val analyzedHigh = createPhoto(actor.requiredId, graph.gallery.id, "quality-analyzed-high")
        val analyzedLow = createPhoto(actor.requiredId, graph.gallery.id, "quality-analyzed-low")
        val legacyHigh = createPhoto(actor.requiredId, graph.gallery.id, "quality-legacy-high")
        listOf(analyzedHigh, analyzedLow, legacyHigh).forEach { photo ->
            setEmbedded(photo.id, unitVector(0), 2400, 1600)
        }
        jdbcClient.sql(
            """
            UPDATE photos
            SET technical_quality_score = CASE id
                    WHEN :highId THEN 92.0
                    WHEN :lowId THEN 12.0
                    ELSE NULL
                END,
                technical_quality_signals = CASE
                    WHEN id IN (:highId, :lowId) THEN '{"algorithmVersion":"technical-v1"}'::JSONB
                    ELSE NULL
                END,
                quality_analyzed_at = CASE
                    WHEN id IN (:highId, :lowId) THEN CURRENT_TIMESTAMP
                    ELSE NULL
                END
            WHERE id IN (:highId, :lowId, :legacyId)
            """.trimIndent(),
        )
            .param("highId", analyzedHigh.id)
            .param("lowId", analyzedLow.id)
            .param("legacyId", legacyHigh.id)
            .update()
        listOf(
            analyzedHigh.id to 1,
            analyzedLow.id to 5,
            legacyHigh.id to 5,
        ).forEach { (photoId, rating) ->
            jdbcClient.sql(
                """
                INSERT INTO photo_ratings (photo_id, score, rated_by, created_at, updated_at)
                VALUES (:photoId, :score, :ratedBy, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            )
                .param("photoId", photoId)
                .param("score", rating)
                .param("ratedBy", graph.user.id)
                .update()
        }

        val draft = execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            selection.id,
            AdminWorkflowAction.CREATE_AI_SELECTION_DRAFT,
            0,
            "selection-technical-quality-001",
            mapOf("requestedCount" to 2),
        )

        assertThat(draft.details["photoIds"] as List<*>)
            .containsExactly(legacyHigh.id, analyzedHigh.id)
        assertThat(
            jdbcClient.sql(
                "SELECT input_conditions->>'algorithm' FROM admin_ai_selection_jobs WHERE selection_id = :selectionId",
            )
                .param("selectionId", selection.id)
                .query { rs, _ -> rs.getString(1) }
                .single(),
        ).isEqualTo("DETERMINISTIC_DIVERSE_V2_TECHNICAL_QUALITY")
    }

    @Test
    fun `스튜디오 템플릿은 같은 스튜디오 앨범에 공유되고 참조를 끊은 뒤에만 삭제된다`() {
        val actor = adminAccountFixture.관리자("workflow-content-owner")
        val graph = createGraph(actor.requiredId, "content", withPhoto = true)
        val photo = requireNotNull(graph.photo)
        val album = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "앨범 생성",
                mapOf("galleryId" to graph.gallery.id, "name" to "본식 앨범"),
            ),
            "127.0.0.1",
        )
        val retouch = resourceService.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest(
                "보정 라운드 생성",
                mapOf("galleryId" to graph.gallery.id, "roundNo" to 1),
            ),
            "127.0.0.1",
        )
        val unrelatedAlbum = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "무관 앨범 생성",
                mapOf("galleryId" to graph.gallery.id, "name" to "무관 앨범"),
            ),
            "127.0.0.1",
        )
        val foreignGraph = createGraph(actor.requiredId, "content-foreign")
        val foreignAlbum = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "다른 스튜디오 앨범 생성",
                mapOf("galleryId" to foreignGraph.gallery.id, "name" to "다른 스튜디오 앨범"),
            ),
            "127.0.0.1",
        )

        val template = execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.CREATE_ALBUM_TEMPLATE,
            0,
            "album-template-create-001",
            mapOf("name" to "12x12 클래식", "layout" to mapOf("columns" to 2, "gutter" to 16)),
        )
        val templateId = (template.details.getValue("templateId") as Number).toLong()
        val foreignTemplateId = jdbcClient.sql(
            """
            INSERT INTO admin_album_templates (studio_id,name,layout_json,version,created_at,updated_at)
            VALUES (:studioId,'다른 스튜디오 전용','{}'::JSONB,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("studioId", foreignGraph.studio.id).query { rs, _ -> rs.getLong(1) }.single()
        assertThat(template.details).containsEntry("albumId", album.id).containsEntry("assigned", true)
        assertThat(
            jdbcClient.sql("SELECT template_id FROM photo_folder_groups WHERE id = :id")
                .param("id", album.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(templateId)
        assertThat(
            jdbcClient.sql("SELECT studio_id FROM admin_album_templates WHERE id = :id")
                .param("id", templateId).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(graph.studio.id)
        assertThat(
            contextService.get(AdminResourceType.ALBUM, album.id).sections.getValue("templates")
                .map { row -> (row.getValue("id") as Number).toLong() },
        ).contains(templateId).doesNotContain(foreignTemplateId)
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.UPDATE_ALBUM_TEMPLATE,
            1,
            "album-template-update-001",
            mapOf(
                "templateId" to templateId,
                "templateExpectedVersion" to 0,
                "name" to "12x12 클래식 v2",
                "layout" to mapOf("columns" to 3, "gutter" to 12),
            ),
        )
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            unrelatedAlbum.id,
            AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
            0,
            "album-template-shared-owner-001",
            mapOf("templateId" to templateId, "folders" to emptyList<Map<String, Any?>>()),
        )
        assertThat(
            jdbcClient.sql("SELECT template_id FROM photo_folder_groups WHERE id = :id")
                .param("id", unrelatedAlbum.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(templateId)
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.ALBUM,
                foreignAlbum.id,
                AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
                0,
                "album-template-cross-studio-001",
                mapOf("templateId" to templateId, "folders" to emptyList<Map<String, Any?>>()),
            )
        }.isInstanceOf(AdminException::class.java)
        assertThat(resourceService.get(AdminResourceType.ALBUM, foreignAlbum.id).version).isZero()
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.UPDATE_ALBUM_TEMPLATE,
            2,
            "album-template-shared-update-001",
            mapOf(
                "templateId" to templateId,
                "templateExpectedVersion" to 1,
                "name" to "12x12 공유 클래식",
                "layout" to mapOf("columns" to 4, "gutter" to 10),
            ),
        )
        val sharedReferences = jdbcClient.sql(
            "SELECT id, template_name, version FROM photo_folder_groups WHERE id IN (:ids) ORDER BY id",
        ).param("ids", listOf(album.id, unrelatedAlbum.id))
            .query { rs, _ -> Triple(rs.getLong("id"), rs.getString("template_name"), rs.getLong("version")) }
            .list()
        assertThat(sharedReferences.map { it.second }).containsOnly("12x12 공유 클래식")
        assertThat(sharedReferences.associate { it.first to it.third })
            .containsEntry(album.id, 3L)
            .containsEntry(unrelatedAlbum.id, 2L)
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.ALBUM,
                album.id,
                AdminWorkflowAction.DELETE_ALBUM_TEMPLATE,
                3,
                "album-template-referenced-delete-001",
                mapOf("templateId" to templateId, "templateExpectedVersion" to 2),
            )
        }.isInstanceOf(AdminException::class.java)
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
            3,
            "album-template-detach-primary-001",
            mapOf("folders" to emptyList<Map<String, Any?>>()),
        )
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            unrelatedAlbum.id,
            AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
            2,
            "album-template-detach-shared-001",
            mapOf("folders" to emptyList<Map<String, Any?>>()),
        )
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.DELETE_ALBUM_TEMPLATE,
            4,
            "album-template-delete-001",
            mapOf("templateId" to templateId, "templateExpectedVersion" to 2),
        )
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.RESTORE_ALBUM_TEMPLATE,
            5,
            "album-template-restore-001",
            mapOf("templateId" to templateId, "templateExpectedVersion" to 3),
        )
        val layout = execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
            6,
            "album-layout-replace-001",
            mapOf(
                "templateId" to templateId,
                "folders" to listOf(
                    mapOf(
                        "name" to "첫 장",
                        "items" to listOf(
                            mapOf(
                                "photoId" to photo.id,
                                "sortOrder" to 0,
                                "crop" to mapOf("x" to 0.1, "y" to 0.2, "width" to 0.8, "height" to 0.7),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertThat(layout.status).isEqualTo("COMPLETED")
        assertThat(layout.details["itemCount"]).isEqualTo(1)
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM photo_folder_items WHERE group_id = :albumId")
                .param("albumId", album.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()

        val createdItem = execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            retouch.id,
            AdminWorkflowAction.CREATE_RETOUCH_ITEM,
            0,
            "retouch-item-create-001",
            mapOf(
                "photoId" to photo.id,
                "requestText" to "인물 피부만 자연스럽게",
                "structuredAiMetadata" to mapOf(
                    "provider" to "operator-assist",
                    "suggestions" to listOf(mapOf("kind" to "SKIN", "strength" to 0.25)),
                ),
            ),
        )
        val retouchPhotoId = (createdItem.details.getValue("retouchPhotoId") as Number).toLong()
        execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            retouch.id,
            AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
            1,
            "retouch-item-update-001",
            mapOf(
                "retouchPhotoId" to retouchPhotoId,
                "retouchPhotoExpectedVersion" to 0,
                "structuredAiMetadata" to mapOf("approved" to true, "strength" to 0.2),
            ),
        )
        val item = jdbcClient.sql(
            "SELECT request_text, structured_ai_metadata::TEXT FROM retouch_photos WHERE id = :id",
        ).param("id", retouchPhotoId)
            .query { rs, _ -> rs.getString("request_text") to rs.getString("structured_ai_metadata") }.single()
        assertThat(item.first).isEqualTo("인물 피부만 자연스럽게")
        assertThat(item.second).contains("approved", "true")

        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                retouch.id,
                AdminWorkflowAction.DELETE_RETOUCH_ITEM,
                2,
                "retouch-item-delete-stale-001",
                mapOf("retouchPhotoId" to retouchPhotoId, "retouchPhotoExpectedVersion" to 0),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            retouch.id,
            AdminWorkflowAction.DELETE_RETOUCH_ITEM,
            2,
            "retouch-item-delete-001",
            mapOf("retouchPhotoId" to retouchPhotoId, "retouchPhotoExpectedVersion" to 1),
        )
        execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            retouch.id,
            AdminWorkflowAction.RESTORE_RETOUCH_ITEM,
            3,
            "retouch-item-restore-001",
            mapOf("retouchPhotoId" to retouchPhotoId, "retouchPhotoExpectedVersion" to 2),
        )
        execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            retouch.id,
            AdminWorkflowAction.UPDATE_RETOUCH_DELIVERY,
            4,
            "retouch-delivery-update-001",
            mapOf(
                "consented" to true,
                "delivered" to true,
                "deliveryNote" to "고객 확인 후 납품",
            ),
        )
        assertThat(
            jdbcClient.sql("SELECT deleted_at IS NULL FROM retouch_photos WHERE id = :id")
                .param("id", retouchPhotoId).query { rs, _ -> rs.getBoolean(1) }.single(),
        ).isTrue()
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_child_trash_records WHERE status = 'RESTORED' AND resource_type IN ('ALBUM_TEMPLATE', 'RETOUCH_ITEM')",
            ).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(2)
        assertThat(
            jdbcClient.sql(
                "SELECT customer_consented_at IS NOT NULL AND delivered_at IS NOT NULL FROM retouch_rounds WHERE id = :id",
            ).param("id", retouch.id).query { rs, _ -> rs.getBoolean(1) }.single(),
        ).isTrue()
        val context = contextService.get(AdminResourceType.RETOUCH_REQUEST, retouch.id)
        assertThat(context.sections.keys).contains("items")
        @Suppress("UNCHECKED_CAST")
        val contextItem = (context.sections.getValue("items") as List<Map<String, Any?>>).single()
        assertThat(contextItem["version"]).isEqualTo(3L)
    }

    @Test
    fun `템플릿 삭제와 동시 레이아웃 배치는 같은 행 잠금으로 직렬화된다`() {
        val actor = adminAccountFixture.관리자("workflow-template-race-owner")
        val graph = createGraph(actor.requiredId, "template-race")
        val deleteParent = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "삭제 기준 앨범 생성",
                mapOf("galleryId" to graph.gallery.id, "name" to "삭제 기준 앨범"),
            ),
            "127.0.0.1",
        )
        val attachTarget = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "배치 대상 앨범 생성",
                mapOf("galleryId" to graph.gallery.id, "name" to "배치 대상 앨범"),
            ),
            "127.0.0.1",
        )
        val templateId = jdbcClient.sql(
            """
            INSERT INTO admin_album_templates (studio_id,name,layout_json,version,created_at,updated_at)
            VALUES (:studioId,'동시성 템플릿','{}'::JSONB,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("studioId", graph.studio.id).query { rs, _ -> rs.getLong(1) }.single()

        val deleteHasRowLock = CountDownLatch(1)
        val allowDeleteCommit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val deleteFuture = pool.submit<Int> {
                transactionTemplate.execute {
                    val updated = childTrashRepository.softDeleteChild(
                        type = AdminChildTrashType.ALBUM_TEMPLATE,
                        resourceId = templateId,
                        parentId = deleteParent.id,
                        expectedChildVersion = 0,
                        deletedAt = ZonedDateTime.now(),
                    )
                    deleteHasRowLock.countDown()
                    check(allowDeleteCommit.await(30, TimeUnit.SECONDS))
                    updated
                } ?: error("delete transaction returned null")
            }
            assertThat(deleteHasRowLock.await(10, TimeUnit.SECONDS)).isTrue()

            val attachFuture = pool.submit<Throwable?> {
                runCatching {
                    execute(
                        actor.requiredId,
                        AdminResourceType.ALBUM,
                        attachTarget.id,
                        AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
                        0,
                        "album-template-race-attach-001",
                        mapOf("templateId" to templateId, "folders" to emptyList<Map<String, Any?>>()),
                    )
                }.exceptionOrNull()
            }
            assertThat(waitForTemplateLockWait()).isTrue()

            allowDeleteCommit.countDown()
            assertThat(deleteFuture.get(10, TimeUnit.SECONDS)).isOne()
            val attachFailure = attachFuture.get(10, TimeUnit.SECONDS)
            assertThat(attachFailure).isInstanceOfSatisfying(AdminException::class.java) {
                assertThat(it.errorCode).isEqualTo(AdminErrorCode.RESOURCE_NOT_FOUND)
            }
            assertThat(
                jdbcClient.sql("SELECT template_id FROM photo_folder_groups WHERE id=:id")
                    .param("id", attachTarget.id)
                    .query { rs, _ -> rs.getLong(1).takeUnless { rs.wasNull() } }
                    .list().single(),
            ).isNull()
            assertThat(
                jdbcClient.sql("SELECT deleted_at IS NOT NULL FROM admin_album_templates WHERE id=:id")
                    .param("id", templateId).query { rs, _ -> rs.getBoolean(1) }.single(),
            ).isTrue()
        } finally {
            allowDeleteCommit.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `앨범 컨텍스트는 100개 뒤 항목도 반환하고 안전 상한 초과를 명시한다`() {
        val actor = adminAccountFixture.관리자("workflow-album-context-limit")
        val graph = createGraph(actor.requiredId, "album-context-limit")
        val album = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "대용량 앨범 컨텍스트 생성",
                mapOf("galleryId" to graph.gallery.id, "name" to "대용량 앨범"),
            ),
            "127.0.0.1",
        )
        val folderId = jdbcClient.sql(
            """
            INSERT INTO photo_folders (group_id,gallery_id,name,version,created_at,updated_at)
            VALUES (:albumId,:galleryId,'대용량 폴더',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("albumId", album.id).param("galleryId", graph.gallery.id)
            .query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            WITH inserted_photos AS (
                INSERT INTO photos
                    (gallery_id,storage_key,original_file_name,display_order,status,content_type,
                     version,created_at,updated_at)
                SELECT :galleryId,
                       'album-context-' || :albumId || '-' || n || '.jpg',
                       'album-context-' || n || '.jpg',
                       n,'UPLOADED','image/jpeg',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                FROM generate_series(1, 2001) AS n
                RETURNING id,display_order
            )
            INSERT INTO photo_folder_items
                (group_id,folder_id,photo_id,sort_order,version,created_at,updated_at)
            SELECT :albumId,:folderId,id,display_order,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            FROM inserted_photos
            """.trimIndent(),
        ).param("galleryId", graph.gallery.id).param("albumId", album.id).param("folderId", folderId).update()

        val context = contextService.get(AdminResourceType.ALBUM, album.id)

        assertThat(context.sections.getValue("items")).hasSize(2_000)
        assertThat(context.sections.getValue("items")[100]["sortOrder"]).isEqualTo(101)
        assertThat(context.sectionPageInfo.getValue("items").totalCount).isEqualTo(2_001)
        assertThat(context.sectionPageInfo.getValue("items").returnedCount).isEqualTo(2_000)
        assertThat(context.sectionPageInfo.getValue("items").truncated).isTrue()
        assertThat(context.sectionPageInfo.getValue("folders").truncated).isFalse()
    }

    @Test
    fun `선택 변경 전 리비전은 기존 보정과 앨범에 고정하고 새 mock 작업은 새 제출 리비전을 참조한다`() {
        val actor = adminAccountFixture.관리자("workflow-revision-link-owner")
        val graph = createGraph(actor.requiredId, "revision-link", withPhoto = true)
        val photo = requireNotNull(graph.photo)
        val selection = resourceService.create(
            actor.requiredId,
            AdminResourceType.SELECTION,
            CreateAdminResourceRequest("리비전 연결 선택", mapOf("galleryId" to graph.gallery.id)),
            "127.0.0.1",
        )
        execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            selection.id,
            AdminWorkflowAction.REPLACE_SELECTION_ITEMS,
            0,
            "revision-link-items-001",
            mapOf("photoIds" to listOf(photo.id)),
        )
        val album = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "리비전 연결 앨범",
                mapOf("galleryId" to graph.gallery.id, "name" to "리비전 앨범"),
            ),
            "127.0.0.1",
        )
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
            0,
            "revision-link-album-001",
            mapOf(
                "folders" to listOf(
                    mapOf("name" to "선택 원본", "items" to listOf(mapOf("photoId" to photo.id))),
                ),
            ),
        )
        val retouch = resourceService.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest(
                "리비전 연결 보정",
                mapOf("galleryId" to graph.gallery.id, "roundNo" to 1),
            ),
            "127.0.0.1",
        )
        resourceService.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            retouch.id,
            UpdateAdminResourceRequest(
                reason = "선택 제출 전 보정 요청 확정",
                expectedVersion = retouch.version,
                fields = mapOf("status" to "REQUESTED"),
            ),
            "127.0.0.1",
        )

        val submitted = execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            selection.id,
            AdminWorkflowAction.SUBMIT_SELECTION_REVISION,
            1,
            "revision-link-submit-001",
        )
        val previousRevisionId = (submitted.details.getValue("previousRevisionId") as Number).toLong()
        val submittedRevisionId = (submitted.details.getValue("revisionId") as Number).toLong()
        val mockJobId = (submitted.details.getValue("mockRecalculationJobId") as Number).toLong()
        assertThat(previousRevisionId).isNotEqualTo(submittedRevisionId)
        assertThat(
            jdbcClient.sql("SELECT selection_revision_id FROM retouch_rounds WHERE id = :id")
                .param("id", retouch.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(previousRevisionId)
        assertThat(
            jdbcClient.sql("SELECT selection_revision_id FROM photo_folder_groups WHERE id = :id")
                .param("id", album.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(previousRevisionId)
        assertThat(
            jdbcClient.sql("SELECT revision_id FROM admin_processing_jobs WHERE id = :id")
                .param("id", mockJobId).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(submittedRevisionId)

        execute(
            actor.requiredId,
            AdminResourceType.SELECTION,
            selection.id,
            AdminWorkflowAction.REPLACE_SELECTION_ITEMS,
            2,
            "revision-link-items-002",
            mapOf("photoIds" to listOf(photo.id)),
        )
        assertThat(
            jdbcClient.sql("SELECT selection_revision_id FROM retouch_rounds WHERE id = :id")
                .param("id", retouch.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(previousRevisionId)
        assertThat(
            jdbcClient.sql("SELECT selection_revision_id FROM photo_folder_groups WHERE id = :id")
                .param("id", album.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isEqualTo(previousRevisionId)
    }

    @Test
    fun `앨범 교체는 sortOrder와 crop 계약 위반을 거절하고 기존 레이아웃을 보존한다`() {
        val actor = adminAccountFixture.관리자("workflow-album-contract-owner")
        val graph = createGraph(actor.requiredId, "album-contract", withPhoto = true)
        val firstPhoto = requireNotNull(graph.photo)
        val secondPhoto = createPhoto(actor.requiredId, graph.gallery.id, "album-contract-second")
        val album = resourceService.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest(
                "앨범 계약 기준 레이아웃",
                mapOf("galleryId" to graph.gallery.id, "name" to "앨범 계약 검증"),
            ),
            "127.0.0.1",
        )
        execute(
            actor.requiredId,
            AdminResourceType.ALBUM,
            album.id,
            AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
            0,
            "album-contract-baseline-001",
            mapOf(
                "folders" to listOf(
                    mapOf(
                        "name" to "기준",
                        "items" to listOf(
                            mapOf(
                                "photoId" to firstPhoto.id,
                                "sortOrder" to 0,
                                "crop" to mapOf("x" to 0.1, "y" to 0.2, "width" to 0.8, "height" to 0.7),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val baseline = albumRows(album.id)

        val invalidLayouts = listOf(
            listOf(
                mapOf("name" to "소수 순서", "items" to listOf(
                    mapOf("photoId" to secondPhoto.id, "sortOrder" to 0.5),
                )),
            ),
            listOf(
                mapOf("name" to "null 순서", "items" to listOf(
                    mapOf("photoId" to secondPhoto.id, "sortOrder" to null),
                )),
            ),
            listOf(
                mapOf("name" to "음수 순서", "items" to listOf(
                    mapOf("photoId" to secondPhoto.id, "sortOrder" to -1),
                )),
            ),
            listOf(
                mapOf("name" to "범위 초과 순서", "items" to listOf(
                    mapOf("photoId" to secondPhoto.id, "sortOrder" to Int.MAX_VALUE.toLong() + 1),
                )),
            ),
            listOf(
                mapOf("name" to "중복 순서", "items" to listOf(
                    mapOf("photoId" to firstPhoto.id, "sortOrder" to 0),
                    mapOf("photoId" to secondPhoto.id, "sortOrder" to 0),
                )),
            ),
            listOf(
                mapOf("name" to "추가 crop key", "items" to listOf(
                    mapOf(
                        "photoId" to secondPhoto.id,
                        "crop" to mapOf("x" to 0, "y" to 0, "width" to 1, "height" to 1, "rotate" to 90),
                    ),
                )),
            ),
            listOf(
                mapOf("name" to "유한하지 않은 crop", "items" to listOf(
                    mapOf(
                        "photoId" to secondPhoto.id,
                        "crop" to mapOf("x" to Double.NaN, "y" to 0, "width" to 1, "height" to 1),
                    ),
                )),
            ),
            listOf(
                mapOf("name" to "정규 범위 밖 crop", "items" to listOf(
                    mapOf(
                        "photoId" to secondPhoto.id,
                        "crop" to mapOf("x" to 0.8, "y" to 0, "width" to 0.3, "height" to 1),
                    ),
                )),
            ),
            List(51) { index -> mapOf("name" to "폴더-$index", "items" to emptyList<Map<String, Any?>>()) },
            listOf(
                mapOf(
                    "name" to "항목 상한 초과",
                    "items" to (1..1_001).map { index ->
                        mapOf("photoId" to 1_000_000L + index, "sortOrder" to index - 1)
                    },
                ),
            ),
        )
        invalidLayouts.forEachIndexed { index, folders ->
            assertThatThrownBy {
                execute(
                    actor.requiredId,
                    AdminResourceType.ALBUM,
                    album.id,
                    AdminWorkflowAction.REPLACE_ALBUM_LAYOUT,
                    1,
                    "album-invalid-${index.toString().padStart(3, '0')}",
                    mapOf("folders" to folders),
                )
            }.isInstanceOfSatisfying(AdminException::class.java) {
                assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
            }
        }

        assertThat(albumRows(album.id)).isEqualTo(baseline)
        assertThat(resourceService.get(AdminResourceType.ALBUM, album.id).version).isEqualTo(1)
    }

    @Test
    fun `보정 산출물 key는 생성 수정으로 직접 입력하거나 다른 회차 항목에 연결할 수 없다`() {
        val actor = adminAccountFixture.관리자("workflow-retouch-key-owner")
        val graph = createGraph(actor.requiredId, "retouch-key", withPhoto = true)
        val photo = requireNotNull(graph.photo)
        val firstRound = resourceService.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest(
                "보정 키 검증",
                mapOf("galleryId" to graph.gallery.id, "roundNo" to 1),
            ),
            "127.0.0.1",
        )

        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                firstRound.id,
                AdminWorkflowAction.CREATE_RETOUCH_ITEM,
                0,
                "retouch-key-cross-gallery-001",
                mapOf(
                    "photoId" to photo.id,
                    "annotationKey" to "galleries/999/retouch/annotations/cross.png",
                ),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val firstItem = execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            firstRound.id,
            AdminWorkflowAction.CREATE_RETOUCH_ITEM,
            0,
            "retouch-key-first-item-001",
            mapOf("photoId" to photo.id),
        )
        val firstItemId = (firstItem.details.getValue("retouchPhotoId") as Number).toLong()
        val secondPhoto = createPhoto(actor.requiredId, graph.gallery.id, "retouch-key-second")
        val secondItem = execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            firstRound.id,
            AdminWorkflowAction.CREATE_RETOUCH_ITEM,
            1,
            "retouch-key-second-item-001",
            mapOf("photoId" to secondPhoto.id),
        )
        val secondItemId = (secondItem.details.getValue("retouchPhotoId") as Number).toLong()

        val issuedKeys = mutableListOf<String>()
        whenever(photoStorage.presignUpload(any(), any())).thenAnswer { invocation ->
            issuedKeys += invocation.getArgument<String>(0)
            PresignedUploadDto("https://upload.example/${issuedKeys.size}", Instant.now().plusSeconds(3_600))
        }
        whenever(photoStorage.exists(any())).thenAnswer { invocation ->
            issuedKeys.contains(invocation.getArgument<String>(0))
        }
        val annotationIssue = execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            firstRound.id,
            AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD,
            2,
            "retouch-key-annotation-issue-001",
            mapOf(
                "retouchPhotoId" to firstItemId,
                "retouchPhotoExpectedVersion" to 0,
                "artifactType" to "ANNOTATION",
                "fileName" to "annotation.png",
                "contentType" to "image/png",
            ),
        )
        execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            firstRound.id,
            AdminWorkflowAction.COMPLETE_RETOUCH_ARTIFACT_UPLOAD,
            3,
            "retouch-key-annotation-complete-001",
            mapOf(
                "uploadId" to (annotationIssue.details.getValue("uploadId") as Number).toLong(),
                "retouchPhotoId" to firstItemId,
                "retouchPhotoExpectedVersion" to 1,
            ),
        )
        val firstItemAnnotationKey = issuedKeys.single()
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                firstRound.id,
                AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
                4,
                "retouch-key-cross-item-001",
                mapOf(
                    "retouchPhotoId" to secondItemId,
                    "retouchPhotoExpectedVersion" to 0,
                    "annotationKey" to firstItemAnnotationKey,
                ),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        resourceService.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            firstRound.id,
            UpdateAdminResourceRequest(
                reason = "결과 key 검증 단계 전환",
                expectedVersion = 4,
                fields = mapOf("status" to "REQUESTED"),
            ),
            "127.0.0.1",
        )

        val resultIssue = execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            firstRound.id,
            AdminWorkflowAction.ISSUE_RETOUCH_ARTIFACT_UPLOAD,
            5,
            "retouch-key-result-issue-001",
            mapOf(
                "retouchPhotoId" to firstItemId,
                "retouchPhotoExpectedVersion" to 2,
                "artifactType" to "RESULT",
                "fileName" to "result.webp",
                "contentType" to "image/webp",
            ),
        )
        execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            firstRound.id,
            AdminWorkflowAction.COMPLETE_RETOUCH_ARTIFACT_UPLOAD,
            6,
            "retouch-key-result-complete-001",
            mapOf(
                "uploadId" to (resultIssue.details.getValue("uploadId") as Number).toLong(),
                "retouchPhotoId" to firstItemId,
                "retouchPhotoExpectedVersion" to 3,
            ),
        )
        val firstRoundResultKey = issuedKeys.last()
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                firstRound.id,
                AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
                7,
                "retouch-key-direct-result-001",
                mapOf(
                    "retouchPhotoId" to secondItemId,
                    "retouchPhotoExpectedVersion" to 0,
                    "resultKey" to firstRoundResultKey,
                    "resultContentType" to "image/webp",
                ),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val secondRound = resourceService.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest(
                "다른 보정 회차",
                mapOf("galleryId" to graph.gallery.id, "roundNo" to 2),
            ),
            "127.0.0.1",
        )
        val crossRoundItem = execute(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            secondRound.id,
            AdminWorkflowAction.CREATE_RETOUCH_ITEM,
            0,
            "retouch-key-cross-round-item-001",
            mapOf("photoId" to photo.id),
        )
        val crossRoundItemId = (crossRoundItem.details.getValue("retouchPhotoId") as Number).toLong()
        resourceService.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            secondRound.id,
            UpdateAdminResourceRequest(
                reason = "다른 회차 결과 key 검증 단계 전환",
                expectedVersion = 1,
                fields = mapOf("status" to "REQUESTED"),
            ),
            "127.0.0.1",
        )
        assertThatThrownBy {
            execute(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                secondRound.id,
                AdminWorkflowAction.UPDATE_RETOUCH_ITEM,
                2,
                "retouch-key-cross-round-result-001",
                mapOf(
                    "retouchPhotoId" to crossRoundItemId,
                    "retouchPhotoExpectedVersion" to 0,
                    "resultKey" to firstRoundResultKey,
                    "resultContentType" to "image/webp",
                ),
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val storedArtifacts = jdbcClient.sql(
            """
            SELECT id, annotation_key, result_key, result_content_type
            FROM retouch_photos
            WHERE id IN (:ids)
            """.trimIndent(),
        )
            .param("ids", listOf(firstItemId, secondItemId, crossRoundItemId))
            .query { rs, _ -> rs.getLong("id") to Triple(
                rs.getString("annotation_key"),
                rs.getString("result_key"),
                rs.getString("result_content_type"),
            ) }
            .list()
            .toMap()
        assertThat(storedArtifacts.getValue(firstItemId))
            .isEqualTo(Triple(firstItemAnnotationKey, firstRoundResultKey, "image/webp"))
        assertThat(storedArtifacts.getValue(secondItemId)).isEqualTo(Triple(null, null, null))
        assertThat(storedArtifacts.getValue(crossRoundItemId)).isEqualTo(Triple(null, null, null))
        verify(photoStorage, times(2)).exists(any())
    }

    private fun execute(
        actorAdminId: Long,
        type: AdminResourceType,
        id: Long,
        action: AdminWorkflowAction,
        expectedVersion: Long,
        idempotencyKey: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = service.execute(
        actorAdminId,
        type,
        id,
        AdminWorkflowRequest(
            action = action,
            reason = "관리자 워크플로 통합 테스트",
            expectedVersion = expectedVersion,
            idempotencyKey = idempotencyKey,
            confirm = true,
            fields = fields,
        ),
        "127.0.0.1",
    )

    private fun waitForTemplateLockWait(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = jdbcClient.sql(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%FROM admin_album_templates t%'
                )
                """.trimIndent(),
            ).query { rs, _ -> rs.getBoolean(1) }.single()
            if (waiting) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun createGraph(actorAdminId: Long, suffix: String, withPhoto: Boolean = false): Graph {
        val user = createUser(actorAdminId, "$suffix-owner")
        val studio = resourceService.create(
            actorAdminId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "워크플로 스튜디오 생성",
                mapOf("ownerUserId" to user.id, "name" to "$suffix 스튜디오", "galleryUrl" to "workflow-$suffix"),
            ),
            "127.0.0.1",
        )
        val gallery = resourceService.create(
            actorAdminId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "워크플로 갤러리 생성",
                mapOf("workspaceId" to studio.id, "title" to "$suffix 갤러리"),
            ),
            "127.0.0.1",
        )
        return Graph(
            resourceService.get(AdminResourceType.USER, user.id),
            studio,
            gallery,
            if (withPhoto) createPhoto(actorAdminId, gallery.id, suffix) else null,
        )
    }

    private fun createUser(actorAdminId: Long, suffix: String): AdminResourceResponse = resourceService.create(
        actorAdminId,
        AdminResourceType.USER,
        CreateAdminResourceRequest(
            "워크플로 사용자 생성",
            mapOf("provider" to "GOOGLE", "providerId" to "workflow-$suffix", "nickname" to suffix),
        ),
        "127.0.0.1",
    )

    private fun createPhoto(actorAdminId: Long, galleryId: Long, suffix: String): AdminResourceResponse =
        resourceService.create(
            actorAdminId,
            AdminResourceType.PHOTO,
            CreateAdminResourceRequest(
                "워크플로 사진 생성",
                mapOf(
                    "galleryId" to galleryId,
                    "storageKey" to "galleries/$galleryId/$suffix.jpg",
                    "originalFileName" to "$suffix.jpg",
                    "contentType" to "image/jpeg",
                    "status" to "UPLOADED",
                ),
            ),
            "127.0.0.1",
        )

    private fun createEmbeddingJob(actorAdminId: Long, photoId: Long, galleryId: Long): Long =
        jdbcClient.sql(
            """
            INSERT INTO admin_processing_jobs (
                job_type, status, target_type, target_id, payload, attempt_count,
                actor_admin_id, reason, created_at, updated_at
            )
            VALUES (
                'EMBEDDING', 'PENDING', 'PHOTO', :photoId,
                jsonb_build_object('galleryId', :galleryId), 0,
                :actorAdminId, 'reasonCategory=TEST_OPERATION operatorReasonProvided=true',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            RETURNING id
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("galleryId", galleryId)
            .param("actorAdminId", actorAdminId)
            .query { rs, _ -> rs.getLong(1) }
            .single()

    private fun prepareAiCategoryAnalysis(galleryId: Long, photoId: Long) {
        jdbcClient.sql(
            """
            INSERT INTO photo_analysis (
                photo_id, embed_group_id, subjects, technical_pct, aesthetic_pct,
                cluster_id, cluster_rank, model_version, analyzed_at, created_at, updated_at
            )
            VALUES (:photoId, 1, 'couple', 80.0, 70.0, 1, 0, 'test-v1',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (photo_id) DO UPDATE
            SET embed_group_id = 1, subjects = 'couple', technical_pct = 80.0,
                aesthetic_pct = 70.0, cluster_id = 1, cluster_rank = 0,
                model_version = 'test-v1', analyzed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("photoId", photoId).update()
        val jobId = jdbcClient.sql(
            """
            INSERT INTO ai_analysis_jobs (
                gallery_id, mode, status, started_at, finished_at, created_at, updated_at
            )
            VALUES (:galleryId, 'FULL', 'DONE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("galleryId", galleryId).query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO ai_concept_assignments (
                job_id, gallery_id, embed_group_id, parent_name, concept_name,
                confidence, assigned_by, needs_review, created_at, updated_at
            )
            VALUES (:jobId, :galleryId, 1, '웨딩', '본식', 0.9, 'vlm', false,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("jobId", jobId).param("galleryId", galleryId).update()
    }

    private fun albumRows(albumId: Long): List<AlbumLayoutRow> = jdbcClient.sql(
        """
        SELECT folder.name, item.photo_id, item.sort_order, item.crop_json::TEXT
        FROM photo_folders folder
        JOIN photo_folder_items item ON item.folder_id = folder.id
        WHERE folder.group_id = :albumId
        ORDER BY folder.id, item.sort_order, item.id
        """.trimIndent(),
    )
        .param("albumId", albumId)
        .query { rs, _ -> AlbumLayoutRow(
            folderName = rs.getString("name"),
            photoId = rs.getLong("photo_id"),
            sortOrder = rs.getInt("sort_order"),
            cropJson = rs.getString("crop_json"),
        ) }
        .list()

    private fun createConceptFolder(galleryId: Long, name: String): Long = jdbcClient.sql(
        """
        INSERT INTO concept_folders
            (gallery_id, name, sort_order, created_source, version, created_at, updated_at)
        VALUES (:galleryId, :name, 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    ).param("galleryId", galleryId).param("name", name)
        .query { rs, _ -> rs.getLong("id") }.single()

    private fun assignPhotoToSession(sessionId: Long, photoId: Long) {
        val detailId = jdbcClient.sql(
            """
            INSERT INTO detail_folders
                (concept_folder_id, name, sort_order, created_source, version, created_at, updated_at)
            SELECT concept_folder_id, '관리자 워크플로 상세', 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM collab_sessions WHERE id = :sessionId
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", sessionId).query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO photo_category_assignments
                (photo_id, detail_folder_id, assigned_source, assigned_at, version, created_at, updated_at)
            VALUES (:photoId, :detailId, 'USER', CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (photo_id) DO UPDATE SET detail_folder_id = EXCLUDED.detail_folder_id,
                assigned_source = 'USER', assigned_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("photoId", photoId).param("detailId", detailId).update()
    }

    private fun userRevisionCount(userId: Long): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM admin_entity_revisions WHERE target_type = 'USER' AND target_id = :targetId",
    )
        .param("targetId", userId.toString())
        .query { rs, _ -> rs.getLong(1) }
        .single()

    private fun userAuditCount(userId: Long): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM admin_audit_logs WHERE target_type = 'USER' AND target_id = :targetId",
    )
        .param("targetId", userId.toString())
        .query { rs, _ -> rs.getLong(1) }
        .single()

    private fun setEmbedded(photoId: Long, embedding: String, width: Int, height: Int) {
        jdbcClient.sql(
            "UPDATE photos SET status = 'EMBEDDED', width = :width, height = :height WHERE id = :id",
        )
            .param("width", width)
            .param("height", height)
            .param("id", photoId)
            .update()
        jdbcClient.sql(
            """
            INSERT INTO photo_analysis (photo_id, embedding, embedding_model, created_at, updated_at)
            VALUES (:id, CAST(:embedding AS vector), 'test-model', now(), now())
            ON CONFLICT (photo_id) DO UPDATE
            SET embedding = EXCLUDED.embedding, updated_at = now()
            """.trimIndent(),
        )
            .param("id", photoId)
            .param("embedding", embedding)
            .update()
    }

    private fun unitVector(index: Int): String = vector { dimension -> if (dimension == index) 1.0 else 0.0 }

    private fun nearVector(first: Int, second: Int): String = vector { dimension ->
        when (dimension) {
            first -> 0.999
            second -> 0.001
            else -> 0.0
        }
    }

    private fun vector(component: (Int) -> Double): String =
        (0 until 768).joinToString(prefix = "[", postfix = "]") { index -> component(index).toString() }

    private data class Graph(
        val user: AdminResourceResponse,
        val studio: AdminResourceResponse,
        val gallery: AdminResourceResponse,
        val photo: AdminResourceResponse?,
    )
    private data class AlbumLayoutRow(
        val folderName: String,
        val photoId: Long,
        val sortOrder: Int,
        val cropJson: String?,
    )
}
