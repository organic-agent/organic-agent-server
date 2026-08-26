package com.soma.wes.admin.resource

import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminReasonRequest
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminCascadeTrashPurgeService
import com.soma.wes.admin.resource.service.AdminCascadeTrashService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.support.TrashEraserTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

@IntegrationTest
@Import(TrashEraserTest.RecordingStorageConfig::class)
class AdminCascadeTrashServiceTest @Autowired constructor(
    private val service: AdminCascadeTrashService,
    private val resourceService: AdminResourceService,
    private val purgeService: AdminCascadeTrashPurgeService,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcClient: JdbcClient,
    private val photoStorage: RecordingTrashPhotoStorage,
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
            "userId" to user.id, "name" to "연쇄 스튜디오", "galleryUrl" to "cascade-gallery",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "studioId" to studio.id, "title" to "연쇄 갤러리",
        ))
        val activePhoto = createPhoto(actor.requiredId, gallery.id, "active.jpg")
        val alreadyTrashedPhoto = createPhoto(actor.requiredId, gallery.id, "already-trashed.jpg")
        jdbcClient.sql("UPDATE photos SET deleted_at = CURRENT_TIMESTAMP - INTERVAL '1 day', version = version + 1 WHERE id = :id")
            .param("id", alreadyTrashedPhoto.id).update()
        val selection = create(actor.requiredId, AdminResourceType.SELECTION, mapOf("galleryId" to gallery.id))
        val collaboration = create(actor.requiredId, AdminResourceType.COLLABORATION, mapOf(
            "galleryId" to gallery.id, "name" to "가족 의견",
        ))
        val album = create(actor.requiredId, AdminResourceType.ALBUM, mapOf(
            "galleryId" to gallery.id, "name" to "후보 앨범",
        ))
        val retouch = create(actor.requiredId, AdminResourceType.RETOUCH_REQUEST, mapOf(
            "galleryId" to gallery.id, "roundNo" to 1,
        ))

        val batch = service.delete(
            actor.requiredId,
            AdminResourceType.GALLERY,
            gallery.id,
            ChangeAdminResourceStateRequest("계약 취소로 전체 숨김", gallery.version),
            "127.0.0.1",
        )

        assertThat(batch.affectedCounts).containsEntry("GALLERY", 1L).containsEntry("PHOTO", 1L)
            .containsEntry("SELECTION", 1L).containsEntry("COLLABORATION", 1L)
            .containsEntry("ALBUM", 1L).containsEntry("RETOUCH_REQUEST", 1L)
        assertThat(deletedAt("photos", activePhoto.id)).isNotNull()
        val preexistingDeletedAt = deletedAt("photos", alreadyTrashedPhoto.id)

        service.restoreBatch(actor.requiredId, batch.id, AdminReasonRequest("계약 복구"), "127.0.0.1")

        assertThat(deletedAt("galleries", gallery.id)).isNull()
        assertThat(deletedAt("photos", activePhoto.id)).isNull()
        assertThat(deletedAt("photos", alreadyTrashedPhoto.id)).isEqualTo(preexistingDeletedAt)
        listOf(
            "photo_selections" to selection.id,
            "collab_sessions" to collaboration.id,
            "photo_folder_groups" to album.id,
            "retouch_rounds" to retouch.id,
        ).forEach { (table, id) -> assertThat(deletedAt(table, id)).isNull() }
    }

    @Test
    fun `사용자 연쇄 삭제는 외부 갤러리 멤버십을 복원하지만 로그인 토큰은 복원하지 않는다`() {
        val actor = adminAccountFixture.관리자("cascade-user")
        val target = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "KAKAO", "providerId" to "cascade-target", "nickname" to "삭제 대상",
        ))
        val targetStudio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "userId" to target.id, "name" to "대상 스튜디오", "galleryUrl" to "cascade-target",
        ))
        create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "studioId" to targetStudio.id, "title" to "대상 갤러리",
        ))
        val other = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "NAVER", "providerId" to "other-owner", "nickname" to "다른 소유자",
        ))
        val otherStudio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "userId" to other.id, "name" to "다른 스튜디오", "galleryUrl" to "other-gallery",
        ))
        val otherGallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "studioId" to otherStudio.id, "title" to "외부 갤러리",
        ))
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
            ChangeAdminResourceStateRequest("사용자 탈퇴 처리", target.version), "127.0.0.1",
        )

        assertThat(batch.affectedCounts).containsEntry("USER", 1L).containsEntry("GALLERY_MEMBER", 1L)
        assertThat(deletedAt("gallery_members", memberId)).isNotNull()
        assertThat(count("refresh_tokens", "user_id", target.id)).isZero()

        service.restoreBatch(actor.requiredId, batch.id, AdminReasonRequest("탈퇴 처리 취소"), "127.0.0.1")

        assertThat(deletedAt("gallery_members", memberId)).isNull()
        assertThat(count("refresh_tokens", "user_id", target.id)).isZero()
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
            "userId" to owner.id, "name" to "충돌 스튜디오", "galleryUrl" to "overlap-gallery",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "studioId" to studio.id, "title" to "충돌 갤러리",
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
    fun `영구 삭제는 스토리지 실패 시 행을 남기고 다음 실행에 재시도한다`() {
        val actor = adminAccountFixture.관리자("cascade-purge")
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "purge-owner", "nickname" to "영구 삭제 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "userId" to user.id, "name" to "영구 삭제 스튜디오", "galleryUrl" to "purge-gallery",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "studioId" to studio.id, "title" to "영구 삭제 갤러리",
        ))
        val photo = createPhoto(actor.requiredId, gallery.id, "purge.jpg")
        val rawStorageKey = storageKey(photo.id)
        val batch = service.delete(
            actor.requiredId, AdminResourceType.GALLERY, gallery.id,
            ChangeAdminResourceStateRequest("복구 기간 후 삭제", gallery.version), "127.0.0.1",
        )
        jdbcClient.sql("UPDATE admin_trash_batches SET restore_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
            .param("id", batch.id).update()

        photoStorage.failDelete = true
        purgeService.purgeExpired()

        assertThat(count("galleries", "id", gallery.id)).isOne()
        assertThat(batchStatus(batch.id)).isEqualTo("ACTIVE")

        photoStorage.failDelete = false
        purgeService.purgeExpired()

        assertThat(count("galleries", "id", gallery.id)).isZero()
        assertThat(batchStatus(batch.id)).isEqualTo("PURGED")
        assertThat(photoStorage.deletedKeys()).contains(rawStorageKey)
    }

    private fun create(actorId: Long, type: AdminResourceType, fields: Map<String, Any?>) =
        resourceService.create(actorId, type, CreateAdminResourceRequest("테스트 데이터 생성", fields), "127.0.0.1")

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

    private fun deletedAt(table: String, id: Long): Long? = jdbcClient.sql(
        "SELECT COALESCE((EXTRACT(EPOCH FROM deleted_at) * 1000000)::BIGINT, -1) FROM $table WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getLong(1) }.single().takeUnless { it == -1L }

    private fun count(table: String, idColumn: String, id: Long): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM $table WHERE $idColumn = :id",
    ).param("id", id).query { rs, _ -> rs.getLong(1) }.single()

    private fun batchStatus(id: Long): String = jdbcClient.sql("SELECT status FROM admin_trash_batches WHERE id = :id")
        .param("id", id).query { rs, _ -> rs.getString(1) }.single()

    private fun storageKey(photoId: Long): String = jdbcClient.sql("SELECT storage_key FROM photos WHERE id = :id")
        .param("id", photoId).query { rs, _ -> rs.getString(1) }.single()
}
