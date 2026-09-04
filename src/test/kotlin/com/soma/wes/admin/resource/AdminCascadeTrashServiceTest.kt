package com.soma.wes.admin.resource

import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminReasonRequest
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.repository.AdminCascadeTrashRepository
import com.soma.wes.admin.resource.service.AdminCascadeTrashPurgeService
import com.soma.wes.admin.resource.service.AdminCascadeTrashService
import com.soma.wes.admin.resource.service.AdminResourceContextService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.RecordingTrashPhotoStorageConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

@IntegrationTest
@Import(RecordingTrashPhotoStorageConfig::class)
class AdminCascadeTrashServiceTest @Autowired constructor(
    private val service: AdminCascadeTrashService,
    private val resourceService: AdminResourceService,
    private val contextService: AdminResourceContextService,
    private val purgeService: AdminCascadeTrashPurgeService,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcClient: JdbcClient,
    private val photoStorage: RecordingTrashPhotoStorage,
    private val cascadeTrashRepository: AdminCascadeTrashRepository,
) {
    @BeforeEach
    fun resetStorage() = photoStorage.reset()

    @Test
    fun `갤러리 연쇄 삭제는 이번 배치의 활성 자식만 복원한다`() {
        val actor = adminAccountFixture.관리자("cascade-gallery")
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "cascade-owner", "nickname" to "연쇄 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to user.id, "name" to "연쇄 스튜디오", "galleryUrl" to "cascade-gallery",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "연쇄 갤러리",
        ))
        val galleryMember = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "NAVER", "providerId" to "cascade-gallery-member", "nickname" to "갤러리 멤버",
        ))
        val galleryMemberId = jdbcClient.sql(
            """
            INSERT INTO gallery_members (gallery_id, user_id, version, created_at, updated_at)
            VALUES (:galleryId, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("galleryId", gallery.id).param("userId", galleryMember.id)
            .query { rs, _ -> rs.getLong("id") }.single()
        val activePhoto = createPhoto(actor.requiredId, gallery.id, "active.jpg")
        val alreadyTrashedPhoto = createPhoto(actor.requiredId, gallery.id, "already-trashed.jpg")
        jdbcClient.sql("UPDATE photos SET deleted_at = CURRENT_TIMESTAMP - INTERVAL '1 day', version = version + 1 WHERE id = :id")
            .param("id", alreadyTrashedPhoto.id).update()
        val selection = create(actor.requiredId, AdminResourceType.SELECTION, mapOf("galleryId" to gallery.id))
        val collaboration = create(actor.requiredId, AdminResourceType.COLLABORATION, mapOf(
            "galleryId" to gallery.id,
            "conceptFolderId" to createConceptFolder(gallery.id),
            "name" to "가족 의견",
        ))
        val retouch = create(actor.requiredId, AdminResourceType.RETOUCH_REQUEST, mapOf(
            "galleryId" to gallery.id, "roundNo" to 1,
        ))
        assignPhotoToSession(collaboration.id, activePhoto.id)
        val collabGuestId = jdbcClient.sql(
            """
            INSERT INTO collab_guests (collab_session_id, guest_token, nickname, version, created_at, updated_at)
            VALUES (:sessionId, 'trash-guest-token', '하객', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", collaboration.id)
            .query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO collab_photo_comments
                (collab_session_id, photo_id, collab_guest_id, content, version, created_at, updated_at)
            VALUES (:sessionId, :photoId, :guestId, '복원되어야 할 댓글', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("sessionId", collaboration.id).param("photoId", activePhoto.id)
            .param("guestId", collabGuestId).update()
        jdbcClient.sql(
            """
            INSERT INTO admin_photo_revisions
                (photo_id, revision_number, storage_key, preview_key, original_file_name, content_type, created_at)
            VALUES (:photoId, 1, 'revisions/photo.jpg', 'revisions/photo-preview.webp',
                    'photo.jpg', 'image/jpeg', CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("photoId", activePhoto.id).update()
        jdbcClient.sql(
            """
            INSERT INTO admin_selection_revisions
                (selection_id, revision_number, source, status, photo_items, actor_admin_id, reason, created_at)
            VALUES (:selectionId, 1, 'ADMIN', 'SELECTING', '[]'::JSONB, :actorId, 'trash facts', CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("selectionId", selection.id).param("actorId", actor.requiredId).update()
        jdbcClient.sql(
            """
            INSERT INTO retouch_photos
                (round_id, gallery_id, photo_id, request_text, annotation_key, result_key,
                 result_content_type, version, created_at, updated_at)
            VALUES (:roundId, :galleryId, :photoId, '보정', 'annotations/a.png', 'results/r.jpg',
                    'image/jpeg', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("roundId", retouch.id).param("galleryId", gallery.id).param("photoId", activePhoto.id).update()

        val batch = service.delete(
            actor.requiredId,
            AdminResourceType.GALLERY,
            gallery.id,
            ChangeAdminResourceStateRequest("계약 취소로 전체 숨김", gallery.version),
            "127.0.0.1",
        )

        assertThat(batch.affectedCounts).containsEntry("GALLERY", 1L).containsEntry("PHOTO", 1L)
            .containsEntry("GALLERY_MEMBER", 1L)
            .containsEntry("SELECTION", 1L).containsEntry("COLLABORATION", 1L)
            .containsEntry("RETOUCH_REQUEST", 1L)
            .containsEntry("COLLAB_COMMENT", 1L)
        assertThat(contextService.get(AdminResourceType.GALLERY, gallery.id).facts)
            .containsEntry("trashBatchId", batch.id)
            .containsEntry("canRestoreDirectly", true)
        assertThat(contextService.get(AdminResourceType.PHOTO, activePhoto.id).facts)
            .containsEntry("trashBatchId", batch.id)
            .containsEntry("canRestoreDirectly", false)
        assertThat(Duration.between(batch.deletedAt, batch.restoreUntil)).isEqualTo(Duration.ofDays(7))
        assertThat(batch.purgeEligibleAt).isEqualTo(batch.restoreUntil)
        assertThat(batch.restoreWindowDays).isEqualTo(7)
        assertThat(batch.restorable).isTrue()
        val durableBatch = jdbcClient.sql(
            "SELECT root_label, actor_username, reason FROM admin_trash_batches WHERE id = :id",
        ).param("id", batch.id).query { rs, _ ->
            Triple(rs.getString("root_label"), rs.getString("actor_username"), rs.getString("reason"))
        }.single()
        assertThat(durableBatch.first).isEqualTo("GALLERY #${gallery.id}")
        assertThat(durableBatch.second).isEqualTo("ADMIN #${actor.requiredId}")
        assertThat(durableBatch.third)
            .isEqualTo("reasonCategory=UNSPECIFIED operatorReasonProvided=true")
        assertThat(batch.entries.count { it.root }).isEqualTo(1)
        assertThat(batch.entries.single { it.root }.relationPath).isEqualTo("ROOT")
        assertThat(batch.entries.single { it.resourceType == "PHOTO" }.relationPath).isEqualTo("GALLERY>PHOTO")
        assertThat(batch.relationshipFacts)
            .containsEntry("entryCount", batch.affectedCounts.values.sum())
            .containsEntry("rootEntryCount", 1L)
            .containsEntry("commentCount", 1L)
            .containsEntry("photoRevisionCount", 1L)
            .containsEntry("selectionRevisionCount", 1L)
            .containsEntry("photoStorageMetadataCount", 2L)
            .containsEntry("retouchStorageMetadataCount", 2L)
        assertThat(batch.relationshipFacts.getValue("entityRevisionCount")).isGreaterThanOrEqualTo(6)
        assertThat(deletedAt("photos", activePhoto.id)).isNotNull()
        assertThat(deletedAt("gallery_members", galleryMemberId)).isNotNull()
        val preexistingDeletedAt = deletedAt("photos", alreadyTrashedPhoto.id)

        val deletedChild = resourceService.get(AdminResourceType.PHOTO, activePhoto.id)
        assertThatThrownBy {
            service.restoreRoot(
                actor.requiredId,
                AdminResourceType.PHOTO,
                activePhoto.id,
                ChangeAdminResourceStateRequest("하위 사진만 복원 시도", deletedChild.version),
                "127.0.0.1",
            )
        }.hasMessageContaining("루트 배치")
        assertThat(deletedAt("photos", activePhoto.id)).isNotNull()

        service.restoreBatch(actor.requiredId, batch.id, AdminReasonRequest("계약 복구"), "127.0.0.1")

        assertThat(deletedAt("galleries", gallery.id)).isNull()
        assertThat(deletedAt("photos", activePhoto.id)).isNull()
        assertThat(deletedAt("gallery_members", galleryMemberId)).isNull()
        assertThat(count("users", "id", galleryMember.id)).isOne()
        assertThat(deletedAt("photos", alreadyTrashedPhoto.id)).isEqualTo(preexistingDeletedAt)
        listOf(
            "photo_selections" to selection.id,
            "collab_sessions" to collaboration.id,
            "retouch_rounds" to retouch.id,
        ).forEach { (table, id) -> assertThat(deletedAt(table, id)).isNull() }
        assertThat(contextService.get(AdminResourceType.GALLERY, gallery.id).facts)
            .containsEntry("trashBatchId", null)
            .containsEntry("canRestoreDirectly", false)
    }

    @Test
    fun `카테고리 폴더는 7일 배치로 하위 폴더와 함께 삭제하고 루트만 직접 복원한다`() {
        val actor = adminAccountFixture.관리자("cascade-category-folder")
        val owner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "category-owner", "nickname" to "카테고리 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to owner.id, "name" to "카테고리 스튜디오", "galleryUrl" to "category-cascade",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "카테고리 갤러리",
        ))
        val concept = create(actor.requiredId, AdminResourceType.CONCEPT_FOLDER, mapOf(
            "galleryId" to gallery.id, "name" to "가족", "sortOrder" to 0,
        ))
        val detail = create(actor.requiredId, AdminResourceType.DETAIL_FOLDER, mapOf(
            "conceptFolderId" to concept.id, "name" to "부모님", "sortOrder" to 0,
        ))

        val batch = service.delete(
            actor.requiredId,
            AdminResourceType.CONCEPT_FOLDER,
            concept.id,
            ChangeAdminResourceStateRequest("카테고리 폴더 삭제", concept.version),
            "127.0.0.1",
        )

        assertThat(batch.restoreWindowDays).isEqualTo(7)
        assertThat(batch.affectedCounts)
            .containsEntry("CONCEPT_FOLDER", 1L)
            .containsEntry("DETAIL_FOLDER", 1L)
        assertThat(resourceService.get(AdminResourceType.CONCEPT_FOLDER, concept.id).deleted).isTrue()
        assertThat(resourceService.get(AdminResourceType.DETAIL_FOLDER, detail.id).deleted).isTrue()
        assertThat(contextService.get(AdminResourceType.CONCEPT_FOLDER, concept.id).facts)
            .containsEntry("trashBatchId", batch.id)
            .containsEntry("canRestoreDirectly", true)
        assertThat(contextService.get(AdminResourceType.DETAIL_FOLDER, detail.id).facts)
            .containsEntry("trashBatchId", batch.id)
            .containsEntry("canRestoreDirectly", false)

        service.restoreBatch(actor.requiredId, batch.id, AdminReasonRequest("카테고리 폴더 복원"), "127.0.0.1")

        assertThat(resourceService.get(AdminResourceType.CONCEPT_FOLDER, concept.id).deleted).isFalse()
        assertThat(resourceService.get(AdminResourceType.DETAIL_FOLDER, detail.id).deleted).isFalse()
    }

    @Test
    fun `선행 product trash descendant claim은 entry가 없어도 USER 관리자 배치를 rollback한다`() {
        val actor = adminAccountFixture.관리자("cascade-product-claim")
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "claim-owner", "nickname" to "claim 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to user.id, "name" to "claim 스튜디오", "galleryUrl" to "claim-scope",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "선행 휴지통 갤러리",
        ))
        val photo = createPhoto(actor.requiredId, gallery.id, "claim-photo.jpg")
        jdbcClient.sql(
            "UPDATE galleries SET deleted_at = CURRENT_TIMESTAMP WHERE id = :galleryId",
        ).param("galleryId", gallery.id).update()
        jdbcClient.sql(
            "UPDATE photos SET deleted_at = CURRENT_TIMESTAMP WHERE id = :photoId",
        ).param("photoId", photo.id).update()
        jdbcClient.sql(
            """
            INSERT INTO product_purge_claims
                (resource_type, resource_id, claim_token, claimed_at, lease_until)
            VALUES ('GALLERY', :galleryId, :token, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '15 minutes')
            """.trimIndent(),
        )
            .param("galleryId", gallery.id)
            .param("token", UUID.randomUUID())
            .update()

        assertThatThrownBy {
            service.delete(
                actor.requiredId,
                AdminResourceType.USER,
                user.id,
                ChangeAdminResourceStateRequest(
                    "product purge와 겹치는 사용자 삭제",
                    resourceService.get(AdminResourceType.USER, user.id).version,
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.TRASH_BATCH_CONFLICT)
        }

        assertThat(deletedAt("users", user.id)).isNull()
        assertThat(deletedAt("studios", studio.id)).isNull()
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_trash_batches WHERE root_type = 'USER' AND root_id = :rootId",
            ).param("rootId", user.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
        assertThat(
            jdbcClient.sql(
                "SELECT COUNT(*) FROM product_purge_claims WHERE resource_type = 'GALLERY' AND resource_id = :id",
            ).param("id", gallery.id).query { rs, _ -> rs.getLong(1) }.single(),
        ).isOne()
    }

    @Test
    fun `사용자 연쇄 삭제는 외부 갤러리 멤버십을 복원하지만 로그인 토큰은 복원하지 않는다`() {
        val actor = adminAccountFixture.관리자("cascade-user")
        val target = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "KAKAO", "providerId" to "cascade-target", "nickname" to "삭제 대상",
        ))
        val targetStudio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to target.id, "name" to "대상 스튜디오", "galleryUrl" to "cascade-target",
        ))
        val personalWorkspaceId = jdbcClient.sql(
            "SELECT id FROM workspaces WHERE type = 'PERSONAL' AND personal_owner_user_id = :userId",
        ).param("userId", target.id).query { rs, _ -> rs.getLong(1) }.single()
        create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to targetStudio.id, "title" to "대상 갤러리",
        ))
        val other = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "NAVER", "providerId" to "other-owner", "nickname" to "다른 소유자",
        ))
        val otherStudio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to other.id, "name" to "다른 스튜디오", "galleryUrl" to "other-gallery",
        ))
        val otherGallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to otherStudio.id, "title" to "외부 갤러리",
        ))
        val externalStudioMemberId = insertStudioMember(otherStudio.id, target.id)
        val ownedStudioMember = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "owned-studio-member", "nickname" to "소유 스튜디오 구성원",
        ))
        val ownedStudioMemberId = insertStudioMember(targetStudio.id, ownedStudioMember.id)
        val memberId = jdbcClient.sql(
            """
            INSERT INTO gallery_members (gallery_id, user_id, version, created_at, updated_at)
            VALUES (:galleryId, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("galleryId", otherGallery.id).param("userId", target.id)
            .query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO refresh_tokens (user_id, token, expires_at, version, created_at, updated_at)
            VALUES (:userId, 'active-refresh', CURRENT_TIMESTAMP + INTERVAL '1 day', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("userId", target.id).update()

        val batch = service.delete(
            actor.requiredId, AdminResourceType.USER, target.id,
            ChangeAdminResourceStateRequest(
                "사용자 탈퇴 처리",
                resourceService.get(AdminResourceType.USER, target.id).version,
            ),
            "127.0.0.1",
        )

        assertThat(batch.affectedCounts)
            .containsEntry("USER", 1L)
            .containsEntry("WORKSPACE", 2L)
            .containsEntry("WORKSPACE_MEMBER", 4L)
            .containsEntry("GALLERY_MEMBER", 1L)
        assertThat(deletedAt("workspaces", personalWorkspaceId)).isNotNull()
        assertThat(deletedAt("workspaces", targetStudio.id)).isNotNull()
        assertThat(deletedAt("workspace_members", externalStudioMemberId)).isNotNull()
        assertThat(deletedAt("workspace_members", ownedStudioMemberId)).isNotNull()
        assertThat(deletedAt("gallery_members", memberId)).isNotNull()
        assertThat(count("refresh_tokens", "user_id", target.id)).isZero()

        service.restoreBatch(actor.requiredId, batch.id, AdminReasonRequest("탈퇴 처리 취소"), "127.0.0.1")

        assertThat(deletedAt("workspace_members", externalStudioMemberId)).isNull()
        assertThat(deletedAt("workspace_members", ownedStudioMemberId)).isNull()
        assertThat(deletedAt("gallery_members", memberId)).isNull()
        assertThat(deletedAt("workspaces", personalWorkspaceId)).isNull()
        assertThat(deletedAt("workspaces", targetStudio.id)).isNull()
        assertThat(count("refresh_tokens", "user_id", target.id)).isZero()

        val restoredTarget = resourceService.get(AdminResourceType.USER, target.id)
        val purgeBatch = service.delete(
            actor.requiredId,
            AdminResourceType.USER,
            target.id,
            ChangeAdminResourceStateRequest(
                "[POLICY_ENFORCEMENT] 사용자 영구 삭제",
                restoredTarget.version,
            ),
            "127.0.0.1",
        )
        jdbcClient.sql(
            "UPDATE admin_trash_batches SET restore_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id",
        ).param("id", purgeBatch.id).update()
        purgeService.purgeExpired()

        assertThat(count("users", "id", target.id)).isZero()
        assertThat(count("studios", "id", targetStudio.id)).isZero()
        assertThat(count("workspace_members", "id", externalStudioMemberId)).isZero()
        assertThat(count("workspace_members", "id", ownedStudioMemberId)).isZero()
        assertThat(count("users", "id", ownedStudioMember.id)).isOne()
        assertThat(count("studios", "id", otherStudio.id)).isOne()
    }

    @Test
    fun `사용자 배치가 외부 멤버십을 보호하는 동안 해당 갤러리 연쇄 삭제를 막는다`() {
        val actor = adminAccountFixture.관리자("cascade-overlap")
        val member = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "KAKAO", "providerId" to "overlap-member", "nickname" to "외부 멤버",
        ))
        val owner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "overlap-owner", "nickname" to "갤러리 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to owner.id, "name" to "충돌 스튜디오", "galleryUrl" to "overlap-gallery",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "충돌 갤러리",
        ))
        jdbcClient.sql(
            """
            INSERT INTO gallery_members (gallery_id, user_id, version, created_at, updated_at)
            VALUES (:galleryId, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("galleryId", gallery.id).param("userId", member.id).update()

        service.delete(
            actor.requiredId, AdminResourceType.USER, member.id,
            ChangeAdminResourceStateRequest("외부 멤버 삭제", member.version), "127.0.0.1",
        )

        assertThatThrownBy {
            service.delete(
                actor.requiredId, AdminResourceType.GALLERY, gallery.id,
                ChangeAdminResourceStateRequest("겹치는 갤러리 삭제", gallery.version), "127.0.0.1",
            )
        }.hasMessageContaining("연쇄 삭제 상태")
    }

    @Test
    fun `스튜디오 연쇄 삭제는 활성 구성원 관계만 묶어 복원하고 8일차에는 관계만 영구 삭제한다`() {
        val actor = adminAccountFixture.관리자("cascade-studio-members")
        val owner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "studio-member-owner", "nickname" to "스튜디오 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to owner.id, "name" to "구성원 대상 스튜디오", "galleryUrl" to "studio-member-cascade",
        ))
        val member = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "KAKAO", "providerId" to "studio-member-user", "nickname" to "스튜디오 구성원",
        ))
        val memberRelationId = insertStudioMember(studio.id, member.id)

        val unrelatedOwner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "NAVER", "providerId" to "studio-member-other-owner", "nickname" to "다른 소유자",
        ))
        val unrelatedStudio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to unrelatedOwner.id, "name" to "무관 스튜디오", "galleryUrl" to "studio-member-unrelated",
        ))
        val unrelatedRelationId = insertStudioMember(unrelatedStudio.id, member.id)

        val batch = service.delete(
            actor.requiredId,
            AdminResourceType.STUDIO,
            studio.id,
            ChangeAdminResourceStateRequest("[CUSTOMER_REQUEST] 스튜디오 휴지통 이동", studio.version),
            "127.0.0.1",
        )

        assertThat(batch.affectedCounts)
            .containsEntry("STUDIO", 1L)
            .containsEntry("WORKSPACE", 1L)
            .containsEntry("WORKSPACE_MEMBER", 2L)
        assertThat(batch.entries.filter { it.resourceType == "WORKSPACE_MEMBER" }.map { it.relationPath })
            .containsOnly("STUDIO>WORKSPACE_MEMBER")
        assertThat(deletedAt("workspace_members", memberRelationId)).isNotNull()
        assertThat(deletedAt("workspaces", studio.id)).isNotNull()
        assertThat(deletedAt("workspace_members", unrelatedRelationId)).isNull()
        assertThat(count("users", "id", member.id)).isOne()

        service.restoreBatch(
            actor.requiredId,
            batch.id,
            AdminReasonRequest("[INCIDENT_RECOVERY] 스튜디오 복원"),
            "127.0.0.1",
        )
        assertThat(deletedAt("workspace_members", memberRelationId)).isNull()
        assertThat(deletedAt("workspaces", studio.id)).isNull()
        assertThat(deletedAt("workspace_members", unrelatedRelationId)).isNull()

        val restoredStudio = resourceService.get(AdminResourceType.STUDIO, studio.id)
        val purgeBatch = service.delete(
            actor.requiredId,
            AdminResourceType.STUDIO,
            studio.id,
            ChangeAdminResourceStateRequest(
                "[POLICY_ENFORCEMENT] 스튜디오 영구 삭제",
                restoredStudio.version,
            ),
            "127.0.0.1",
        )
        jdbcClient.sql(
            "UPDATE admin_trash_batches SET restore_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id",
        ).param("id", purgeBatch.id).update()
        purgeService.purgeExpired()

        assertThat(count("studios", "id", studio.id)).isZero()
        assertThat(count("workspace_members", "id", memberRelationId)).isZero()
        assertThat(count("users", "id", owner.id)).isOne()
        assertThat(count("users", "id", member.id)).isOne()
        assertThat(count("studios", "id", unrelatedStudio.id)).isOne()
        assertThat(count("workspace_members", "id", unrelatedRelationId)).isOne()
    }

    @Test
    fun `영구 삭제는 스토리지 실패 시 행을 남기고 다음 실행에 재시도한다`() {
        val actor = adminAccountFixture.관리자("cascade-purge")
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "purge-owner", "nickname" to "영구 삭제 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to user.id, "name" to "영구 삭제 스튜디오", "galleryUrl" to "purge-gallery",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "영구 삭제 갤러리",
        ))
        val member = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "NAVER", "providerId" to "purge-member", "nickname" to "남아야 할 멤버",
        ))
        val memberRelationId = jdbcClient.sql(
            """
            INSERT INTO gallery_members (gallery_id, user_id, version, created_at, updated_at)
            VALUES (:galleryId, :userId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("galleryId", gallery.id).param("userId", member.id)
            .query { rs, _ -> rs.getLong("id") }.single()
        val photo = createPhoto(actor.requiredId, gallery.id, "purge.jpg")
        val siblingGallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "보존 대상 갤러리",
        ))
        val siblingPhoto = createPhoto(actor.requiredId, siblingGallery.id, "sibling.jpg")
        insertProcessingJob(photo.id, "SUCCEEDED", "purge-target")
        insertNotification(photo.id, "SENT", "purge-target")
        insertIdempotency(photo.id, "COMPLETED", "purge-target")
        insertProcessingJob(siblingPhoto.id, "SUCCEEDED", "purge-sibling")
        insertNotification(siblingPhoto.id, "SENT", "purge-sibling")
        insertIdempotency(siblingPhoto.id, "COMPLETED", "purge-sibling")
        val retouchArtifactKey = insertRetouchArtifact(gallery.id, photo.id, "cascade-purge")
        val historicalKeys = insertPhotoHistory(photo.id, "cascade-purge")
        val rawStorageKey = storageKey(photo.id)
        val batch = service.delete(
            actor.requiredId, AdminResourceType.GALLERY, gallery.id,
            ChangeAdminResourceStateRequest("복구 기간 후 삭제", gallery.version), "127.0.0.1",
        )
        assertThat(Duration.between(batch.deletedAt, batch.restoreUntil)).isEqualTo(Duration.ofDays(7))
        assertThat(batch.purgeEligibleAt).isEqualTo(batch.restoreUntil)
        jdbcClient.sql("UPDATE admin_trash_batches SET restore_until = CURRENT_TIMESTAMP + INTERVAL '1 minute' WHERE id = :id")
            .param("id", batch.id).update()
        purgeService.purgeExpired()
        assertThat(batchStatus(batch.id)).isEqualTo("ACTIVE")
        jdbcClient.sql("UPDATE admin_trash_batches SET restore_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
            .param("id", batch.id).update()

        photoStorage.failDelete = true
        purgeService.purgeExpired()

        assertThat(count("galleries", "id", gallery.id)).isOne()
        assertThat(batchStatus(batch.id)).isEqualTo("ACTIVE")
        assertThat(count("admin_processing_jobs", "target_id", photo.id)).isOne()
        assertThat(count("admin_notification_outbox", "source_id", photo.id)).isOne()
        assertThat(idempotencyStatuses(photo.id)).hasSize(1)

        photoStorage.failDelete = false
        purgeService.purgeExpired()
        assertThat(count("galleries", "id", gallery.id)).isOne()
        jdbcClient.sql(
            "UPDATE admin_trash_batches SET next_purge_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id",
        ).param("id", batch.id).update()
        purgeService.purgeExpired()

        assertThat(count("galleries", "id", gallery.id)).isZero()
        assertThat(count("users", "id", member.id)).isOne()
        assertThat(count("gallery_members", "id", memberRelationId)).isZero()
        assertThat(count("admin_processing_jobs", "target_id", photo.id)).isZero()
        assertThat(count("admin_notification_outbox", "source_id", photo.id)).isZero()
        assertThat(idempotencyStatuses(photo.id)).isEmpty()
        assertThat(count("admin_processing_jobs", "target_id", siblingPhoto.id)).isOne()
        assertThat(count("admin_notification_outbox", "source_id", siblingPhoto.id)).isOne()
        assertThat(idempotencyStatuses(siblingPhoto.id)).hasSize(1)
        assertThat(batchStatus(batch.id)).isEqualTo("PURGED")
        assertThat(photoStorage.deletedKeys()).contains(rawStorageKey, retouchArtifactKey)
        assertThat(photoStorage.deletedKeys()).containsAll(historicalKeys)
    }

    @Test
    fun `연쇄 purge는 limit보다 큰 burst를 비우고 poison 배치는 backoff 뒤 dead-letter로 격리한다`() {
        val actor = adminAccountFixture.관리자("cascade-purge-fairness")
        val owner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE",
            "providerId" to "cascade-purge-fairness-owner",
            "nickname" to "대량 삭제 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to owner.id,
            "name" to "대량 삭제 스튜디오",
            "galleryUrl" to "cascade-purge-fairness",
        ))
        val batchToGallery = (1..22).associate { index ->
            val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
                "workspaceId" to studio.id,
                "title" to "대량 삭제 갤러리 $index",
            ))
            val batch = service.delete(
                actor.requiredId,
                AdminResourceType.GALLERY,
                gallery.id,
                ChangeAdminResourceStateRequest("[TEST_OPERATION] 대량 삭제 $index", gallery.version),
                "127.0.0.1",
            )
            batch.id to gallery.id
        }
        jdbcClient.sql(
            "UPDATE admin_trash_batches SET restore_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'",
        ).update()

        val claimNow = ZonedDateTime.now()
        val poison = cascadeTrashRepository.claimExpired(
            claimNow,
            claimNow.minusHours(2),
            limit = 1,
        ).single()
        cascadeTrashRepository.markPurgeFailed(
            batchId = poison.id,
            failureCode = "POISON_TEST",
            nextAttemptAt = claimNow.plusDays(1),
            maxAttempts = 5,
        )

        // poison 하나를 제외해도 21건이라 기본 claim limit(20)을 넘어 두 batch가 필요하다.
        purgeService.purgeExpired()

        assertThat(batchStatus(poison.id)).isEqualTo("ACTIVE")
        assertThat(count("galleries", "id", batchToGallery.getValue(poison.id))).isOne()
        assertThat(countBatchStatus("PURGED")).isEqualTo(21)
        batchToGallery.filterKeys { it != poison.id }.values.forEach { galleryId ->
            assertThat(count("galleries", "id", galleryId)).isZero()
        }

        repeat(4) { index ->
            val retryNow = claimNow.plusDays((index + 2).toLong())
            val claimedAgain = cascadeTrashRepository.claimExpired(
                retryNow,
                retryNow.minusHours(2),
                limit = 1,
            ).single()
            assertThat(claimedAgain.id).isEqualTo(poison.id)
            cascadeTrashRepository.markPurgeFailed(
                batchId = poison.id,
                failureCode = "POISON_TEST_${index + 2}",
                nextAttemptAt = retryNow.plusMinutes(5),
                maxAttempts = 5,
            )
        }
        assertThat(batchStatus(poison.id)).isEqualTo("PURGE_FAILED")
        assertThat(
            cascadeTrashRepository.claimExpired(
                claimNow.plusDays(30),
                claimNow.plusDays(30).minusHours(2),
                limit = 20,
            ),
        ).isEmpty()
    }

    @Test
    fun `연쇄 삭제는 배치 대상의 대기 작업만 취소하고 완료 및 무관 작업은 보존한다`() {
        val actor = adminAccountFixture.관리자("cascade-cancel-pending")
        val owner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "cancel-owner", "nickname" to "취소 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to owner.id, "name" to "취소 스튜디오", "galleryUrl" to "cancel-pending",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "취소 대상 갤러리",
        ))
        val photo = createPhoto(actor.requiredId, gallery.id, "cancel.jpg")
        val selection = create(actor.requiredId, AdminResourceType.SELECTION, mapOf("galleryId" to gallery.id))

        val otherOwner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "NAVER", "providerId" to "cancel-other", "nickname" to "무관 소유자",
        ))
        val otherStudio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to otherOwner.id, "name" to "무관 스튜디오", "galleryUrl" to "cancel-other",
        ))
        val otherGallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to otherStudio.id, "title" to "무관 갤러리",
        ))
        val otherPhoto = createPhoto(actor.requiredId, otherGallery.id, "other.jpg")
        val otherSelection = create(
            actor.requiredId,
            AdminResourceType.SELECTION,
            mapOf("galleryId" to otherGallery.id),
        )

        listOf("PENDING", "DISPATCHING", "DISPATCHED", "FAILED", "SUCCEEDED").forEachIndexed { index, status ->
            insertProcessingJob(photo.id, status, "main-$index")
        }
        insertProcessingJob(otherPhoto.id, "PENDING", "unrelated")
        listOf("PENDING", "RUNNING", "FAILED", "SUCCEEDED").forEach { status ->
            insertAiJob(selection.id, status)
        }
        insertAiJob(otherSelection.id, "PENDING")
        listOf("PENDING", "SENDING", "FAILED", "SENT").forEachIndexed { index, status ->
            insertNotification(photo.id, status, "main-$index")
        }
        insertNotification(otherPhoto.id, "PENDING", "unrelated")
        insertReplacementUpload(photo.id, "PENDING", "main-pending")
        insertReplacementUpload(photo.id, "COMPLETED", "main-completed")
        insertReplacementUpload(otherPhoto.id, "PENDING", "unrelated")
        listOf("PENDING", "FAILED", "COMPLETED").forEachIndexed { index, status ->
            insertIdempotency(photo.id, status, "main-$index")
        }
        insertIdempotency(otherPhoto.id, "PENDING", "unrelated")

        service.delete(
            actor.requiredId,
            AdminResourceType.GALLERY,
            gallery.id,
            ChangeAdminResourceStateRequest("[CUSTOMER_REQUEST] 취소 대상 삭제", gallery.version),
            "127.0.0.1",
        )

        assertThat(statuses("admin_processing_jobs", "target_id", photo.id))
            .containsExactlyInAnyOrder("CANCELED", "CANCELED", "CANCELED", "CANCELED", "SUCCEEDED")
        assertThat(statuses("admin_ai_selection_jobs", "selection_id", selection.id))
            .containsExactlyInAnyOrder("CANCELED", "CANCELED", "CANCELED", "SUCCEEDED")
        assertThat(statuses("admin_notification_outbox", "source_id", photo.id))
            .containsExactlyInAnyOrder("CANCELED", "CANCELED", "CANCELED", "SENT")
        assertThat(statuses("admin_photo_replacement_uploads", "photo_id", photo.id))
            .containsExactlyInAnyOrder("EXPIRED", "COMPLETED")
        assertThat(idempotencyStatuses(photo.id))
            .containsExactlyInAnyOrder("CANCELED", "CANCELED", "COMPLETED")

        assertThat(statuses("admin_processing_jobs", "target_id", otherPhoto.id)).containsExactly("PENDING")
        assertThat(statuses("admin_ai_selection_jobs", "selection_id", otherSelection.id)).containsExactly("PENDING")
        assertThat(statuses("admin_notification_outbox", "source_id", otherPhoto.id)).containsExactly("PENDING")
        assertThat(statuses("admin_photo_replacement_uploads", "photo_id", otherPhoto.id))
            .containsExactly("PENDING")
        assertThat(idempotencyStatuses(otherPhoto.id)).containsExactly("PENDING")
    }

    @Test
    fun `기존 배치 없는 갤러리와 사진도 삭제 시각부터 7일이 지나면 복원할 수 없다`() {
        val actor = adminAccountFixture.관리자("legacy-cutoff")
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "legacy-owner", "nickname" to "기존 삭제 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to user.id, "name" to "기존 삭제 스튜디오", "galleryUrl" to "legacy-cutoff",
        ))
        val expiredGallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "만료 갤러리",
        ))
        val activeGallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "사진 부모 갤러리",
        ))
        val expiredPhoto = createPhoto(actor.requiredId, activeGallery.id, "legacy-expired.jpg")
        listOf(
            "galleries" to expiredGallery.id,
            "photos" to expiredPhoto.id,
        ).forEach { (table, id) ->
            jdbcClient.sql(
                "UPDATE $table SET deleted_at = CURRENT_TIMESTAMP - INTERVAL '8 days', version = version + 1 WHERE id = :id",
            ).param("id", id).update()
        }

        listOf(
            AdminResourceType.GALLERY to expiredGallery.id,
            AdminResourceType.PHOTO to expiredPhoto.id,
        ).forEach { (type, id) ->
            val current = resourceService.get(type, id)
            assertThatThrownBy {
                service.restoreRoot(
                    actor.requiredId,
                    type,
                    id,
                    ChangeAdminResourceStateRequest("만료된 기존 데이터 복원 시도", current.version),
                    "127.0.0.1",
                )
            }.hasMessageContaining("7일")
        }

        jdbcClient.sql(
            "UPDATE galleries SET deleted_at = CURRENT_TIMESTAMP - INTERVAL '6 days' WHERE id = :id",
        ).param("id", expiredGallery.id).update()
        val restorable = resourceService.get(AdminResourceType.GALLERY, expiredGallery.id)
        val restored = service.restoreRoot(
            actor.requiredId,
            AdminResourceType.GALLERY,
            expiredGallery.id,
            ChangeAdminResourceStateRequest("7일 내 기존 데이터 복원", restorable.version),
            "127.0.0.1",
        )
        assertThat(restored.deleted).isFalse()
    }

    private fun create(actorId: Long, type: AdminResourceType, fields: Map<String, Any?>) =
        resourceService.create(actorId, type, CreateAdminResourceRequest("테스트 데이터 생성", fields), "127.0.0.1")

    private fun createConceptFolder(galleryId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO concept_folders
            (gallery_id, name, sort_order, created_source, version, created_at, updated_at)
        VALUES (:galleryId, '연쇄 삭제 컨셉', 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    ).param("galleryId", galleryId).query { rs, _ -> rs.getLong("id") }.single()

    private fun assignPhotoToSession(sessionId: Long, photoId: Long) {
        val detailId = jdbcClient.sql(
            """
            INSERT INTO detail_folders
                (concept_folder_id, name, sort_order, created_source, version, created_at, updated_at)
            SELECT concept_folder_id, '연쇄 삭제 상세', 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM collab_sessions WHERE id = :sessionId
            RETURNING id
            """.trimIndent(),
        ).param("sessionId", sessionId).query { rs, _ -> rs.getLong("id") }.single()
        jdbcClient.sql(
            """
            INSERT INTO photo_category_assignments
                (photo_id, detail_folder_id, assigned_source, assigned_at, version, created_at, updated_at)
            VALUES (:photoId, :detailId, 'USER', CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("photoId", photoId).param("detailId", detailId).update()
    }

    private fun createPhoto(actorId: Long, galleryId: Long, name: String) = create(
        actorId,
        AdminResourceType.PHOTO,
        mapOf(
            "galleryId" to galleryId,
            "storageKey" to "galleries/$galleryId/$name",
            "originalFileName" to name,
            "contentType" to "image/jpeg",
        ),
    )

    private fun insertProcessingJob(targetId: Long, status: String, suffix: String) {
        jdbcClient.sql(
            """
            INSERT INTO admin_processing_jobs
                (job_type, status, target_type, target_id, payload, attempt_count,
                 reason, created_at, updated_at)
            VALUES
                ('DERIVATIVE', :status, 'PHOTO', :targetId, CAST(:payload AS JSONB), 0,
                 'reasonCategory=TEST_OPERATION operatorReasonProvided=true',
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("status", status).param("targetId", targetId)
            .param("payload", "{\"case\":\"$suffix\"}").update()
    }

    private fun insertStudioMember(studioId: Long, userId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO workspace_members (workspace_id, user_id, role, version, created_at, updated_at)
        VALUES (:studioId, :userId, 'MEMBER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    ).param("studioId", studioId).param("userId", userId)
        .query { rs, _ -> rs.getLong("id") }.single()

    private fun insertRetouchArtifact(galleryId: Long, photoId: Long, suffix: String): String {
        val roundId = jdbcClient.sql(
            """
            INSERT INTO retouch_rounds
                (gallery_id, round_no, status, version, created_at, updated_at)
            VALUES (:galleryId, 1, 'DRAFTING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .query { rs, _ -> rs.getLong("id") }
            .single()
        val retouchPhotoId = jdbcClient.sql(
            """
            INSERT INTO retouch_photos
                (round_id, gallery_id, photo_id, version, created_at, updated_at)
            VALUES (:roundId, :galleryId, :photoId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("galleryId", galleryId)
            .param("photoId", photoId)
            .query { rs, _ -> rs.getLong("id") }
            .single()
        val storageKey = "retouch-artifacts/$roundId/$retouchPhotoId/$suffix.jpg"
        jdbcClient.sql(
            """
            INSERT INTO admin_retouch_artifact_uploads
                (round_id, retouch_photo_id, artifact_type, storage_key, original_file_name,
                 content_type, status, expires_at, reason, created_at)
            VALUES (:roundId, :retouchPhotoId, 'RESULT', :storageKey, :fileName,
                    'image/jpeg', 'PENDING', CURRENT_TIMESTAMP + INTERVAL '1 day',
                    'test purge', CURRENT_TIMESTAMP)
            """.trimIndent(),
        )
            .param("roundId", roundId)
            .param("retouchPhotoId", retouchPhotoId)
            .param("storageKey", storageKey)
            .param("fileName", "$suffix.jpg")
            .update()
        return storageKey
    }

    private fun insertPhotoHistory(photoId: Long, suffix: String): Set<String> {
        val revisionStorageKey = "revisions/$suffix/original.jpg"
        val revisionPreviewKey = "revisions/$suffix/preview.jpg"
        val replacementStorageKey = "replacement/$suffix.jpg"
        jdbcClient.sql(
            """
            INSERT INTO admin_photo_revisions
                (photo_id, revision_number, storage_key, preview_key,
                 original_file_name, content_type, created_at)
            VALUES (:photoId, 1, :storageKey, :previewKey,
                    'original.jpg', 'image/jpeg', CURRENT_TIMESTAMP)
            """.trimIndent(),
        )
            .param("photoId", photoId)
            .param("storageKey", revisionStorageKey)
            .param("previewKey", revisionPreviewKey)
            .update()
        insertReplacementUpload(photoId, "PENDING", suffix)
        return setOf(
            revisionStorageKey,
            revisionPreviewKey,
            "previews/${revisionStorageKey.substringBeforeLast('.')}.jpg",
            replacementStorageKey,
            "previews/${replacementStorageKey.substringBeforeLast('.')}.jpg",
        )
    }

    private fun insertAiJob(selectionId: Long, status: String) {
        jdbcClient.sql(
            """
            INSERT INTO admin_ai_selection_jobs
                (selection_id, status, input_conditions, attempt_count, reason, created_at, updated_at)
            VALUES
                (:selectionId, :status, '{}'::JSONB, 0,
                 'reasonCategory=TEST_OPERATION operatorReasonProvided=true',
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("selectionId", selectionId).param("status", status).update()
    }

    private fun insertNotification(sourceId: Long, status: String, suffix: String) {
        jdbcClient.sql(
            """
            INSERT INTO admin_notification_outbox
                (notification_type, recipient_reference, source_type, source_id, payload,
                 status, attempt_count, reason, created_at, updated_at)
            VALUES
                ('PHOTO_READY', :recipient, 'PHOTO', :sourceId, '{}'::JSONB,
                 :status, 0, 'reasonCategory=TEST_OPERATION operatorReasonProvided=true',
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("recipient", "recipient-$suffix").param("sourceId", sourceId)
            .param("status", status).update()
    }

    private fun insertReplacementUpload(photoId: Long, status: String, suffix: String) {
        jdbcClient.sql(
            """
            INSERT INTO admin_photo_replacement_uploads
                (photo_id, storage_key, original_file_name, content_type, status, expires_at,
                 reason, created_at, completed_at)
            VALUES
                (:photoId, :storageKey, :fileName, 'image/jpeg', :status,
                 CURRENT_TIMESTAMP + INTERVAL '1 day',
                 'reasonCategory=TEST_OPERATION operatorReasonProvided=true',
                 CURRENT_TIMESTAMP, CASE WHEN :status = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END)
            """.trimIndent(),
        ).param("photoId", photoId).param("storageKey", "replacement/$suffix.jpg")
            .param("fileName", "$suffix.jpg").param("status", status).update()
    }

    private fun insertIdempotency(photoId: Long, status: String, suffix: String) {
        jdbcClient.sql(
            """
            INSERT INTO admin_idempotency_keys
                (action, idempotency_key, request_hash, status, target_type, target_id,
                 correlation_id, created_at, updated_at)
            VALUES
                ('REPROCESS_PHOTO', :key, :hash, :status, 'PHOTO', :targetId,
                 '0123456789abcdef', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("key", "cancel-$photoId-$suffix").param("hash", "hash-$photoId-$suffix")
            .param("status", status).param("targetId", photoId.toString()).update()
    }

    private fun idempotencyStatuses(targetId: Long): List<String> = jdbcClient.sql(
        "SELECT status FROM admin_idempotency_keys WHERE target_type = 'PHOTO' AND target_id = :id ORDER BY id",
    ).param("id", targetId.toString()).query { rs, _ -> rs.getString("status") }.list()

    private fun statuses(table: String, idColumn: String, id: Long): List<String> = jdbcClient.sql(
        "SELECT status FROM $table WHERE $idColumn = :id ORDER BY id",
    ).param("id", id).query { rs, _ -> rs.getString("status") }.list()

    private fun deletedAt(table: String, id: Long): Long? = jdbcClient.sql(
        "SELECT COALESCE((EXTRACT(EPOCH FROM deleted_at) * 1000000)::BIGINT, -1) FROM $table WHERE ${idColumn(table, "id")} = :id",
    ).param("id", id).query { rs, _ -> rs.getLong(1) }.single().takeUnless { it == -1L }

    private fun count(table: String, idColumn: String, id: Long): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM $table WHERE ${idColumn(table, idColumn)} = :id",
    ).param("id", id).query { rs, _ -> rs.getLong(1) }.single()

    private fun idColumn(table: String, requested: String): String =
        if (table == "studios" && requested == "id") "workspace_id" else requested

    private fun batchStatus(id: Long): String = jdbcClient.sql("SELECT status FROM admin_trash_batches WHERE id = :id")
        .param("id", id).query { rs, _ -> rs.getString(1) }.single()

    private fun countBatchStatus(status: String): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM admin_trash_batches WHERE status = :status",
    ).param("status", status).query { rs, _ -> rs.getLong(1) }.single()

    private fun storageKey(photoId: Long): String = jdbcClient.sql("SELECT storage_key FROM photos WHERE id = :id")
        .param("id", photoId).query { rs, _ -> rs.getString(1) }.single()
}
