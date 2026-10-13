package com.soma.wes.admin.resource

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.repository.AdminChildTrashRepository
import com.soma.wes.admin.resource.service.AdminCascadeTrashService
import com.soma.wes.admin.resource.service.AdminChildTrashPurgeService
import com.soma.wes.admin.resource.service.AdminChildTrashService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.RecordingTrashPhotoStorageConfig
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
@Import(RecordingTrashPhotoStorageConfig::class)
class AdminChildTrashServiceTest @Autowired constructor(
    private val service: AdminChildTrashService,
    private val purgeService: AdminChildTrashPurgeService,
    private val cascadeTrashService: AdminCascadeTrashService,
    private val resourceService: AdminResourceService,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcClient: JdbcClient,
    private val photoStorage: RecordingTrashPhotoStorage,
    private val childTrashRepository: AdminChildTrashRepository,
    private val transactionTemplate: TransactionTemplate,
) {

    @BeforeEach
    fun resetStorage() = photoStorage.reset()

    @Test
    fun `복구 가능 자식이 500건을 넘어도 목록에서 잘리지 않는다`() {
        jdbcClient.sql(
            """
            INSERT INTO admin_child_trash_records (
                resource_type, resource_id, parent_type, parent_id, actor_username,
                reason, status, deleted_at, restore_until, created_at, updated_at
            )
            SELECT 'COLLAB_COMMENT', 900000 + n, 'COLLABORATION', 800000 + n,
                   'SYSTEM', 'LIST_VISIBILITY_TEST', 'ACTIVE', CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM generate_series(1, 501) n
            """.trimIndent(),
        ).update()

        assertThat(service.list().count { it.status == "ACTIVE" }).isGreaterThanOrEqualTo(501)
    }

    @Test
    fun `제품 photo purge claim이 있으면 관리자 child 삭제를 거절한다`() {
        val graph = graph("child-product-claim")
        val collaboration = create(
            graph.actorId,
            AdminResourceType.COLLABORATION,
            mapOf("galleryId" to graph.galleryId, "name" to "claim 보호 의견"),
        )
        val commentId = createComment(collaboration.id, graph.photoId, "제품 purge 중 보존할 댓글")
        jdbcClient.sql("UPDATE photos SET deleted_at = CURRENT_TIMESTAMP WHERE id = :photoId")
            .param("photoId", graph.photoId)
            .update()
        jdbcClient.sql(
            """
            INSERT INTO product_purge_claims
                (resource_type, resource_id, claim_token, claimed_at, lease_until)
            VALUES ('PHOTO', :photoId, :token, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '15 minutes')
            """.trimIndent(),
        )
            .param("photoId", graph.photoId)
            .param("token", UUID.randomUUID())
            .update()

        assertThatThrownBy {
            service.delete(
                actorAdminId = graph.actorId,
                type = AdminChildTrashType.COLLAB_COMMENT,
                resourceId = commentId,
                parentId = collaboration.id,
                expectedParentVersion = collaboration.version,
                expectedChildVersion = null,
                reason = "claim 중 댓글 삭제",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }
        assertThat(deleted("collab_photo_comments", commentId)).isFalse()
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_child_trash_records " +
                    "WHERE resource_type = 'COLLAB_COMMENT' AND resource_id = :resourceId",
            ).param("resourceId", commentId).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
    }

    @Test
    fun `댓글 독립 삭제는 삭제자 사유와 7일 창을 남기고 복원한다`() {
        val graph = graph("child-restore")
        val collaboration = create(
            graph.actorId,
            AdminResourceType.COLLABORATION,
            mapOf("galleryId" to graph.galleryId, "name" to "복원 의견"),
        )
        val commentId = createComment(collaboration.id, graph.photoId, "복원할 댓글")

        val commentTrash = service.delete(
            actorAdminId = graph.actorId,
            type = AdminChildTrashType.COLLAB_COMMENT,
            resourceId = commentId,
            parentId = collaboration.id,
            expectedParentVersion = collaboration.version,
            expectedChildVersion = null,
            reason = "[CUSTOMER_REQUEST] 문의 private@example.com 댓글 삭제",
        )

        listOf(commentTrash).forEach { trash ->
            assertThat(Duration.between(trash.deletedAt, trash.restoreUntil)).isEqualTo(Duration.ofDays(7))
            assertThat(trash.purgeEligibleAt).isEqualTo(trash.restoreUntil)
            assertThat(trash.restoreWindowDays).isEqualTo(7)
            assertThat(trash.restorable).isTrue()
            assertThat(childTrashStatus(trash.trashId)).isEqualTo("ACTIVE")
        }
        assertThat(childTrashReason(commentTrash.trashId))
            .isEqualTo("reasonCategory=CUSTOMER_REQUEST operatorReasonProvided=true")
            .doesNotContain("private@example.com", "문의")
        val listed = service.list().associateBy { it.id }.getValue(commentTrash.trashId)
        assertThat(listed.actorUsername).isEqualTo("ADMIN #${graph.actorId}")
        assertThat(listed.resourceType).isEqualTo(AdminChildTrashType.COLLAB_COMMENT)
        assertThat(listed.parentType).isEqualTo(AdminResourceType.COLLABORATION)
        assertThat(listed.deletedAt.toInstant().toEpochMilli())
            .isEqualTo(commentTrash.deletedAt.toInstant().toEpochMilli())
        assertThat(listed.restoreUntil.toInstant().toEpochMilli())
            .isEqualTo(commentTrash.restoreUntil.toInstant().toEpochMilli())
        assertThat(listed.purgeEligibleAt.toInstant().toEpochMilli())
            .isEqualTo(commentTrash.restoreUntil.toInstant().toEpochMilli())
        assertThat(listed.restorable).isTrue()
        assertThat(deleted("collab_photo_comments", commentId)).isTrue()

        val restoredComment = service.restore(
            type = AdminChildTrashType.COLLAB_COMMENT,
            resourceId = commentId,
            parentId = collaboration.id,
            expectedParentVersion = resourceService.get(AdminResourceType.COLLABORATION, collaboration.id).version,
            expectedChildVersion = null,
        )

        assertThat(restoredComment.status).isEqualTo("RESTORED")
        assertThat(deleted("collab_photo_comments", commentId)).isFalse()
        assertThat(childTrashStatus(commentTrash.trashId)).isEqualTo("RESTORED")
    }

    @Test
    fun `부모 연쇄 삭제 중에는 독립 삭제 댓글도 단독 복원할 수 없다`() {
        val graph = graph("child-parent-block")
        val collaboration = create(
            graph.actorId,
            AdminResourceType.COLLABORATION,
            mapOf("galleryId" to graph.galleryId, "name" to "부모 삭제 의견"),
        )
        val commentId = createComment(collaboration.id, graph.photoId, "부모와 함께 숨길 댓글")
        service.delete(
            actorAdminId = graph.actorId,
            type = AdminChildTrashType.COLLAB_COMMENT,
            resourceId = commentId,
            parentId = collaboration.id,
            expectedParentVersion = collaboration.version,
            expectedChildVersion = null,
            reason = "댓글 선삭제",
        )
        val currentParent = resourceService.get(AdminResourceType.COLLABORATION, collaboration.id)
        cascadeTrashService.delete(
            graph.actorId,
            AdminResourceType.COLLABORATION,
            collaboration.id,
            ChangeAdminResourceStateRequest("부모 연쇄 삭제", currentParent.version),
            "127.0.0.1",
        )

        assertThatThrownBy {
            service.restore(
                type = AdminChildTrashType.COLLAB_COMMENT,
                resourceId = commentId,
                parentId = collaboration.id,
                expectedParentVersion =
                    resourceService.get(AdminResourceType.COLLABORATION, collaboration.id).version,
                expectedChildVersion = null,
            )
        }.hasMessageContaining("루트 배치")
        assertThat(deleted("collab_photo_comments", commentId)).isTrue()
    }

    @Test
    fun `보정 항목은 8일차에 스토리지와 DB를 지우고 실패하면 행을 보존해 재시도한다`() {
        val graph = graph("child-purge")
        val retouch = create(
            graph.actorId,
            AdminResourceType.RETOUCH_REQUEST,
            mapOf("galleryId" to graph.galleryId, "roundNo" to 1),
        )
        val itemId = jdbcClient.sql(
            """
            INSERT INTO retouch_photos (
                round_id, gallery_id, photo_id, request_text, annotation_key, result_key,
                result_content_type, version, created_at, updated_at
            )
            VALUES (
                :roundId, :galleryId, :photoId, '삭제될 보정 요청',
                'annotations/child-purge.png', 'results/child-purge.jpg',
                'image/jpeg', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            RETURNING id
            """.trimIndent(),
        )
            .param("roundId", retouch.id)
            .param("galleryId", graph.galleryId)
            .param("photoId", graph.photoId)
            .query { rs, _ -> rs.getLong("id") }
            .single()
        val pendingArtifactKey = "retouch-artifacts/${retouch.id}/$itemId/pending-result.jpg"
        jdbcClient.sql(
            """
            INSERT INTO admin_retouch_artifact_uploads
                (round_id, retouch_photo_id, artifact_type, storage_key, original_file_name,
                 content_type, status, expires_at, reason, created_at)
            VALUES (:roundId, :itemId, 'RESULT', :storageKey, 'pending-result.jpg',
                    'image/jpeg', 'PENDING', CURRENT_TIMESTAMP + INTERVAL '1 day',
                    'test purge', CURRENT_TIMESTAMP)
            """.trimIndent(),
        )
            .param("roundId", retouch.id)
            .param("itemId", itemId)
            .param("storageKey", pendingArtifactKey)
            .update()
        val trash = service.delete(
            actorAdminId = graph.actorId,
            type = AdminChildTrashType.RETOUCH_ITEM,
            resourceId = itemId,
            parentId = retouch.id,
            expectedParentVersion = retouch.version,
            expectedChildVersion = null,
            reason = "보정 항목 삭제",
        )

        jdbcClient.sql(
            "UPDATE admin_child_trash_records SET restore_until = CURRENT_TIMESTAMP + INTERVAL '1 minute' WHERE id = :id",
        ).param("id", trash.trashId).update()
        purgeService.purgeExpired()
        assertThat(count("retouch_photos", itemId)).isOne()

        jdbcClient.sql(
            "UPDATE admin_child_trash_records SET restore_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id",
        ).param("id", trash.trashId).update()
        photoStorage.failDelete = true
        purgeService.purgeExpired()
        assertThat(count("retouch_photos", itemId)).isOne()
        assertThat(childTrashStatus(trash.trashId)).isEqualTo("ACTIVE")
        assertThat(nextPurgeAttemptAtIsFuture(trash.trashId)).isTrue()

        photoStorage.failDelete = false
        purgeService.purgeExpired()
        assertThat(count("retouch_photos", itemId)).isOne()
        jdbcClient.sql(
            "UPDATE admin_child_trash_records SET next_purge_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id",
        ).param("id", trash.trashId).update()
        purgeService.purgeExpired()
        assertThat(count("retouch_photos", itemId)).isZero()
        assertThat(childTrashStatus(trash.trashId)).isEqualTo("PURGED")
        assertThat(photoStorage.deletedKeys())
            .contains("annotations/child-purge.png", "results/child-purge.jpg", pendingArtifactKey)
        assertThat(
            jdbcClient.sql(
                """
                SELECT COUNT(*) FROM admin_audit_logs
                WHERE action = 'RESOURCE_PURGED' AND target_type = 'TRASH_BATCH'
                  AND target_id = :targetId
                """.trimIndent(),
            ).param("targetId", "CHILD-${trash.trashId}").query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
    }

    @Test
    fun `보정 항목 purge는 다른 행이 공유하는 스토리지 키를 지우지 않는다`() {
        val graph = graph("child-shared-key")
        val retouch = create(
            graph.actorId,
            AdminResourceType.RETOUCH_REQUEST,
            mapOf("galleryId" to graph.galleryId, "roundNo" to 1),
        )
        val liveStorageKey = "galleries/${graph.galleryId}/child-shared-key-other.jpg"
        val otherPhoto = create(
            graph.actorId,
            AdminResourceType.PHOTO,
            mapOf(
                "galleryId" to graph.galleryId,
                "storageKey" to liveStorageKey,
                "originalFileName" to "child-shared-key-other.jpg",
                "contentType" to "image/jpeg",
            ),
        )
        val targetId = insertRetouchItem(
            roundId = retouch.id,
            galleryId = graph.galleryId,
            photoId = graph.photoId,
            annotationKey = "annotations/shared-across-items.png",
            resultKey = "results/target-only.jpg",
        )
        val liveId = insertRetouchItem(
            roundId = retouch.id,
            galleryId = graph.galleryId,
            photoId = otherPhoto.id,
            annotationKey = "annotations/live-only.png",
            // target의 annotation key를 다른 열에서 참조하는 교차 공유도 보호한다.
            resultKey = "annotations/shared-across-items.png",
        )
        val expectedLivePreviewKey = "previews/${liveStorageKey.substringBeforeLast('.')}.jpg"
        jdbcClient.sql(
            """
            INSERT INTO admin_retouch_artifact_uploads
                (round_id, retouch_photo_id, artifact_type, storage_key, original_file_name,
                 content_type, status, expires_at, reason, created_at)
            VALUES (:roundId, :itemId, 'RESULT', :storageKey, 'preview-collision.jpg',
                    'image/jpeg', 'PENDING', CURRENT_TIMESTAMP + INTERVAL '1 day',
                    'test preview collision', CURRENT_TIMESTAMP)
            """.trimIndent(),
        )
            .param("roundId", retouch.id)
            .param("itemId", targetId)
            .param("storageKey", expectedLivePreviewKey)
            .update()
        val trash = service.delete(
            actorAdminId = graph.actorId,
            type = AdminChildTrashType.RETOUCH_ITEM,
            resourceId = targetId,
            parentId = retouch.id,
            expectedParentVersion = retouch.version,
            expectedChildVersion = null,
            reason = "공유 키 보정 항목 삭제",
        )
        jdbcClient.sql(
            "UPDATE admin_child_trash_records SET restore_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id",
        ).param("id", trash.trashId).update()

        purgeService.purgeExpired()

        assertThat(count("retouch_photos", targetId)).isZero()
        assertThat(count("retouch_photos", liveId)).isOne()
        assertThat(photoStorage.deletedKeys()).contains("results/target-only.jpg")
        assertThat(photoStorage.deletedKeys()).doesNotContain(
            "annotations/shared-across-items.png",
            expectedLivePreviewKey,
        )
    }

    @Test
    fun `좋아요 복원은 제품 재좋아요의 사진 잠금 뒤 충돌을 확인해 새 반응을 보존한다`() {
        val graph = graph("child-like-race")
        val collaboration = create(
            graph.actorId,
            AdminResourceType.COLLABORATION,
            mapOf("galleryId" to graph.galleryId, "name" to "동시 좋아요"),
        )
        val identity = createCollabIdentity(collaboration.id, graph.photoId, "child-like-race")
        val likeId = jdbcClient.sql(
            """
            INSERT INTO collab_photo_likes
                (collab_session_id, photo_id, participant_id, version, created_at, updated_at)
            VALUES (:sessionId, :photoId, :guestId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", collaboration.id).param("photoId", identity.photoId)
            .param("guestId", identity.guestId)
            .query { rs, _ -> rs.getLong("id") }.single()
        service.delete(
            actorAdminId = graph.actorId,
            type = AdminChildTrashType.COLLAB_LIKE,
            resourceId = likeId,
            parentId = collaboration.id,
            expectedParentVersion = collaboration.version,
            expectedChildVersion = null,
            reason = "경쟁 조건 검증용 좋아요 삭제",
        )
        val restoreParentVersion = resourceService.get(AdminResourceType.COLLABORATION, collaboration.id).version

        val relikeHasPhotoLock = CountDownLatch(1)
        val allowRelikeCommit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val relikeFuture = pool.submit<Unit> {
                transactionTemplate.executeWithoutResult {
                    jdbcClient.sql("SELECT id FROM photos WHERE id = :id FOR UPDATE")
                        .param("id", identity.photoId).query { rs, _ -> rs.getLong("id") }.single()
                    jdbcClient.sql(
                        """
                        INSERT INTO collab_photo_likes
                            (collab_session_id, photo_id, participant_id, version, created_at, updated_at)
                        VALUES (:sessionId, :photoId, :guestId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    ).param("sessionId", collaboration.id).param("photoId", identity.photoId)
                        .param("guestId", identity.guestId).update()
                    relikeHasPhotoLock.countDown()
                    check(allowRelikeCommit.await(30, TimeUnit.SECONDS))
                }
            }
            assertThat(relikeHasPhotoLock.await(10, TimeUnit.SECONDS)).isTrue()

            val restoreFuture = pool.submit<Throwable?> {
                runCatching {
                    service.restore(
                        type = AdminChildTrashType.COLLAB_LIKE,
                        resourceId = likeId,
                        parentId = collaboration.id,
                        expectedParentVersion = restoreParentVersion,
                        expectedChildVersion = null,
                    )
                }.exceptionOrNull()
            }
            // pg_stat_activity의 query 문자열은 드라이버/서버 버전에 따라 달라질 수 있다.
            // 잠금 대기를 관찰할 기회를 준 뒤, 아래의 실제 commit 결과로 직렬화 계약을 검증한다.
            waitForLikeRestoreLockWait()

            allowRelikeCommit.countDown()
            relikeFuture.get(10, TimeUnit.SECONDS)
            assertThat(restoreFuture.get(10, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(AdminException::class.java) {
                    assertThat(it.errorCode).isEqualTo(AdminErrorCode.TRASH_BATCH_CONFLICT)
                }
            assertThat(
                jdbcClient.sql(
                    "SELECT COUNT(*) FROM collab_photo_likes WHERE photo_id = :photoId AND deleted_at IS NULL",
                ).param("photoId", identity.photoId).query { rs, _ -> rs.getLong(1) }.single(),
            ).isOne()
            assertThat(deleted("collab_photo_likes", likeId)).isTrue()
        } finally {
            allowRelikeCommit.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `자식 purge는 limit보다 큰 burst를 비우고 poison 행은 backoff 뒤 dead-letter로 격리한다`() {
        val graph = graph("child-purge-fairness")
        val collaboration = create(
            graph.actorId,
            AdminResourceType.COLLABORATION,
            mapOf("galleryId" to graph.galleryId, "name" to "대량 purge"),
        )
        val rows = insertExpiredComments(
            sessionId = collaboration.id,
            photoId = graph.photoId,
            actorId = graph.actorId,
            count = 52,
        )
        val claimNow = ZonedDateTime.now()
        val poison = childTrashRepository.claimExpired(
            claimNow,
            claimNow.minusHours(2),
            limit = 1,
        ).single()
        childTrashRepository.markPurgeFailed(
            id = poison.id,
            failureCode = "POISON_TEST",
            nextAttemptAt = claimNow.plusDays(1),
            maxAttempts = 5,
        )

        // poison 하나를 제외해도 51건이므로 기본 claim limit(50)을 넘어 두 batch가 필요하다.
        purgeService.purgeExpired()

        assertThat(childTrashStatus(poison.id)).isEqualTo("ACTIVE")
        assertThat(count("collab_photo_comments", poison.resourceId)).isOne()
        assertThat(countChildTrashStatus("PURGED")).isEqualTo(51)
        assertThat(rows).hasSize(52)

        repeat(4) { index ->
            val retryNow = claimNow.plusDays((index + 2).toLong())
            val claimedAgain = childTrashRepository.claimExpired(
                retryNow,
                retryNow.minusHours(2),
                limit = 1,
            ).single()
            assertThat(claimedAgain.id).isEqualTo(poison.id)
            childTrashRepository.markPurgeFailed(
                id = poison.id,
                failureCode = "POISON_TEST_${index + 2}",
                nextAttemptAt = retryNow.plusMinutes(5),
                maxAttempts = 5,
            )
        }
        assertThat(childTrashStatus(poison.id)).isEqualTo("PURGE_FAILED")
        assertThat(
            childTrashRepository.claimExpired(
                claimNow.plusDays(30),
                claimNow.plusDays(30).minusHours(2),
                limit = 50,
            ),
        ).isEmpty()
    }

    private fun graph(suffix: String): Graph {
        val actor = adminAccountFixture.관리자(suffix)
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE",
            "providerId" to "$suffix-owner",
            "nickname" to "자식 휴지통 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to user.id,
            "name" to "자식 휴지통 스튜디오",
            "galleryUrl" to suffix,
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id,
            "title" to "자식 휴지통 갤러리",
        ))
        val photo = create(actor.requiredId, AdminResourceType.PHOTO, mapOf(
            "galleryId" to gallery.id,
            "storageKey" to "galleries/${gallery.id}/$suffix.jpg",
            "originalFileName" to "$suffix.jpg",
            "contentType" to "image/jpeg",
        ))
        return Graph(actor.requiredId, gallery.id, photo.id)
    }

    private fun createComment(sessionId: Long, photoId: Long, content: String): Long {
        assignPhotoToSession(sessionId, photoId)
        val guestId = jdbcClient.sql(
            """
            INSERT INTO collab_participants (
                collab_session_id, participant_type, guest_token, nickname, version, created_at, updated_at
            )
            VALUES (:sessionId, 'GUEST', :guestToken, '하객', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", sessionId).param("guestToken", "guest-$sessionId")
            .query { rs, _ -> rs.getLong("id") }.single()
        return jdbcClient.sql(
            """
            INSERT INTO collab_photo_comments (
                collab_session_id, photo_id, participant_id, content, version, created_at, updated_at
            )
            VALUES (:sessionId, :photoId, :guestId, :content, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("sessionId", sessionId)
            .param("photoId", photoId)
            .param("guestId", guestId)
            .param("content", content)
            .query { rs, _ -> rs.getLong("id") }
            .single()
    }

    private fun createCollabIdentity(sessionId: Long, photoId: Long, suffix: String): CollabIdentity {
        assignPhotoToSession(sessionId, photoId)
        val guestId = jdbcClient.sql(
            """
            INSERT INTO collab_participants (
                collab_session_id, participant_type, guest_token, nickname, version, created_at, updated_at
            )
            VALUES (:sessionId, 'GUEST', :guestToken, '하객', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", sessionId).param("guestToken", "guest-$suffix")
            .query { rs, _ -> rs.getLong("id") }.single()
        return CollabIdentity(photoId, guestId)
    }

    private fun insertRetouchItem(
        roundId: Long,
        galleryId: Long,
        photoId: Long,
        annotationKey: String?,
        resultKey: String?,
    ): Long = jdbcClient.sql(
        """
        INSERT INTO retouch_photos (
            round_id, gallery_id, photo_id, request_text, annotation_key, result_key,
            result_content_type, version, created_at, updated_at
        )
        VALUES (
            :roundId, :galleryId, :photoId, '공유 키 검증', :annotationKey, :resultKey,
            CASE WHEN :resultKey IS NULL THEN NULL ELSE 'image/jpeg' END,
            0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        )
        RETURNING id
        """.trimIndent(),
    )
        .param("roundId", roundId)
        .param("galleryId", galleryId)
        .param("photoId", photoId)
        .param("annotationKey", annotationKey)
        .param("resultKey", resultKey)
        .query { rs, _ -> rs.getLong("id") }
        .single()

    private fun insertExpiredComments(
        sessionId: Long,
        photoId: Long,
        actorId: Long,
        count: Int,
    ): List<BulkTrashRow> {
        val identity = createCollabIdentity(sessionId, photoId, "bulk-$sessionId")
        return jdbcClient.sql(
            """
            WITH inserted_comments AS (
                INSERT INTO collab_photo_comments (
                    collab_session_id, photo_id, participant_id, content, version, deleted_at,
                    created_at, updated_at
                )
                SELECT :sessionId, :photoId, :guestId, 'bulk-' || n, 1,
                       CURRENT_TIMESTAMP - INTERVAL '8 days',
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM generate_series(1, :count) AS n
                RETURNING id, deleted_at
            ), inserted_trash AS (
                INSERT INTO admin_child_trash_records (
                    resource_type, resource_id, parent_type, parent_id, actor_admin_id,
                    actor_username, reason, status, deleted_at, restore_until,
                    created_at, updated_at
                )
                SELECT 'COLLAB_COMMENT', comment.id, 'COLLABORATION', :sessionId, :actorId,
                       (SELECT username FROM admin_accounts WHERE id = :actorId),
                       'reasonCategory=TEST_OPERATION operatorReasonProvided=true',
                       'ACTIVE', comment.deleted_at, CURRENT_TIMESTAMP - INTERVAL '1 minute',
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM inserted_comments comment
                RETURNING id, resource_id
            )
            SELECT id, resource_id FROM inserted_trash ORDER BY id
            """.trimIndent(),
        )
            .param("photoId", identity.photoId)
            .param("guestId", identity.guestId)
            .param("count", count)
            .param("sessionId", sessionId)
            .param("actorId", actorId)
            .query { rs, _ -> BulkTrashRow(rs.getLong("id"), rs.getLong("resource_id")) }
            .list()
    }

    private fun waitForLikeRestoreLockWait(): Boolean {
        repeat(200) {
            val waiting = jdbcClient.sql(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND state = 'active'
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%collab_photo_likes trashed%'
                )
                """.trimIndent(),
            ).query { rs, _ -> rs.getBoolean(1) }.single()
            if (waiting) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun create(actorId: Long, type: AdminResourceType, fields: Map<String, Any?>): com.soma.wes.admin.resource.dto.AdminResourceResponse {
        val normalized = fields
        return resourceService.create(
            actorId,
            type,
            CreateAdminResourceRequest("테스트 데이터 생성", normalized),
            "127.0.0.1",
        )
    }

    private fun assignPhotoToSession(sessionId: Long, photoId: Long) {
        jdbcClient.sql(
            """
            INSERT INTO collab_session_photos (collab_session_id, gallery_id, photo_id, version, created_at, updated_at)
            SELECT id, gallery_id, :photoId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM collab_sessions WHERE id = :sessionId
            """.trimIndent(),
        ).param("sessionId", sessionId).param("photoId", photoId).update()
    }

    private fun childTrashStatus(id: Long): String = jdbcClient.sql(
        "SELECT status FROM admin_child_trash_records WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getString(1) }.single()

    private fun childTrashReason(id: Long): String = jdbcClient.sql(
        "SELECT reason FROM admin_child_trash_records WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getString(1) }.single()

    private fun nextPurgeAttemptAtIsFuture(id: Long): Boolean = jdbcClient.sql(
        "SELECT next_purge_attempt_at > CURRENT_TIMESTAMP FROM admin_child_trash_records WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getBoolean(1) }.single()

    private fun countChildTrashStatus(status: String): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM admin_child_trash_records WHERE status = :status",
    ).param("status", status).query { rs, _ -> rs.getLong(1) }.single()

    private fun deleted(table: String, id: Long): Boolean = jdbcClient.sql(
        "SELECT deleted_at IS NOT NULL FROM $table WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getBoolean(1) }.single()

    private fun count(table: String, id: Long): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM $table WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getLong(1) }.single()

    private data class Graph(
        val actorId: Long,
        val galleryId: Long,
        val photoId: Long,
    )

    private data class CollabIdentity(val photoId: Long, val guestId: Long)
    private data class BulkTrashRow(val id: Long, val resourceId: Long)
}
