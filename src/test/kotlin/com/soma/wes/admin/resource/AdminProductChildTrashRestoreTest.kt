package com.soma.wes.admin.resource

import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminChildTrashType
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminChildTrashService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.service.ProductChildTrashService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/** 제품 API가 만든 휴지통 레코드를 관리자 API가 별도 변환 없이 복원할 수 있는지 검증한다. */
@IntegrationTest
class AdminProductChildTrashRestoreTest @Autowired constructor(
    private val adminChildTrashService: AdminChildTrashService,
    private val resourceService: AdminResourceService,
    private val adminAccountFixture: AdminAccountFixture,
    private val productChildTrashService: ProductChildTrashService,
    private val transactionTemplate: TransactionTemplate,
    private val jdbcClient: JdbcClient,
) {

    @Test
    fun `제품에서 지운 댓글 좋아요 보정 항목을 관리자가 7일 안에 복원한다`() {
        val actor = adminAccountFixture.관리자("product-child-restore")
        val user = create(
            actor.requiredId,
            AdminResourceType.USER,
            mapOf(
                "provider" to "GOOGLE",
                "providerId" to "product-child-restore-owner",
                "nickname" to "제품 휴지통 소유자",
            ),
        )
        val studio = create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            mapOf(
                "userId" to user.id,
                "name" to "제품 휴지통 스튜디오",
                "galleryUrl" to "product-child-restore",
            ),
        )
        val gallery = create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            mapOf("studioId" to studio.id, "title" to "제품 휴지통 갤러리"),
        )
        val photo = create(
            actor.requiredId,
            AdminResourceType.PHOTO,
            mapOf(
                "galleryId" to gallery.id,
                "storageKey" to "galleries/${gallery.id}/product-child-restore.jpg",
                "originalFileName" to "product-child-restore.jpg",
                "contentType" to "image/jpeg",
            ),
        )
        val collaboration = create(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            mapOf("galleryId" to gallery.id, "name" to "제품 휴지통 복원"),
        )
        val retouch = create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            mapOf("galleryId" to gallery.id, "roundNo" to 1),
        )
        val collabPhotoId = insertCollabPhoto(collaboration.id, photo.id)
        val guestId = insertGuest(collaboration.id)
        val commentId = insertComment(collabPhotoId, guestId)

        transactionTemplate.executeWithoutResult {
            assertThat(
                productChildTrashService.deleteGuestComment(collaboration.id, commentId, guestId),
            ).isTrue()
        }
        restore(
            type = AdminChildTrashType.COLLAB_COMMENT,
            resourceId = commentId,
            parentTable = "collab_sessions",
            parentId = collaboration.id,
            childTable = "collab_photo_comments",
        )

        val likeId = insertLike(collabPhotoId, guestId)
        transactionTemplate.executeWithoutResult {
            assertThat(
                productChildTrashService.cancelGuestLike(collaboration.id, collabPhotoId, guestId),
            ).isTrue()
        }
        restore(
            type = AdminChildTrashType.COLLAB_LIKE,
            resourceId = likeId,
            parentTable = "collab_sessions",
            parentId = collaboration.id,
            childTable = "collab_photo_likes",
        )

        val retouchItemId = insertRetouchItem(retouch.id, gallery.id, photo.id)
        transactionTemplate.executeWithoutResult {
            assertThat(productChildTrashService.removeUserRetouchItem(retouch.id, photo.id)).isTrue()
        }
        restore(
            type = AdminChildTrashType.RETOUCH_ITEM,
            resourceId = retouchItemId,
            parentTable = "retouch_rounds",
            parentId = retouch.id,
            childTable = "retouch_photos",
        )
    }

    private fun create(actorId: Long, type: AdminResourceType, fields: Map<String, Any?>) =
        resourceService.create(
            actorId,
            type,
            CreateAdminResourceRequest("제품 휴지통 복원 테스트 데이터 생성", fields),
            "127.0.0.1",
        )

    private fun insertCollabPhoto(sessionId: Long, photoId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO collab_photos (collab_session_id, photo_id, version, created_at, updated_at)
        VALUES (:sessionId, :photoId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    )
        .param("sessionId", sessionId)
        .param("photoId", photoId)
        .query { rs, _ -> rs.getLong("id") }
        .single()

    private fun insertGuest(sessionId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO collab_guests (
            collab_session_id, guest_token, nickname, version, created_at, updated_at
        )
        VALUES (:sessionId, 'product-child-restore-guest', '복원 하객', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    )
        .param("sessionId", sessionId)
        .query { rs, _ -> rs.getLong("id") }
        .single()

    private fun insertComment(collabPhotoId: Long, guestId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO collab_photo_comments (
            collab_photo_id, collab_guest_id, content, version, created_at, updated_at
        )
        VALUES (:collabPhotoId, :guestId, '복원할 댓글', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    )
        .param("collabPhotoId", collabPhotoId)
        .param("guestId", guestId)
        .query { rs, _ -> rs.getLong("id") }
        .single()

    private fun insertLike(collabPhotoId: Long, guestId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO collab_photo_likes (
            collab_photo_id, collab_guest_id, version, created_at, updated_at
        )
        VALUES (:collabPhotoId, :guestId, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    )
        .param("collabPhotoId", collabPhotoId)
        .param("guestId", guestId)
        .query { rs, _ -> rs.getLong("id") }
        .single()

    private fun insertRetouchItem(roundId: Long, galleryId: Long, photoId: Long): Long = jdbcClient.sql(
        """
        INSERT INTO retouch_photos (
            round_id, gallery_id, photo_id, request_text, version, created_at, updated_at
        )
        VALUES (:roundId, :galleryId, :photoId, '제품에서 제거할 보정 항목', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    )
        .param("roundId", roundId)
        .param("galleryId", galleryId)
        .param("photoId", photoId)
        .query { rs, _ -> rs.getLong("id") }
        .single()

    private fun restore(
        type: AdminChildTrashType,
        resourceId: Long,
        parentTable: String,
        parentId: Long,
        childTable: String,
    ) {
        val parentVersion = version(parentTable, parentId)
        val childVersion = version(childTable, resourceId)
        assertSoftly { softly ->
            softly.assertThat(deleted(childTable, resourceId)).isTrue()
            softly.assertThat(childVersion).isEqualTo(1L)
            softly.assertThat(childTrashStatus(type, resourceId)).isEqualTo("ACTIVE")
        }

        val restored = adminChildTrashService.restore(
            type = type,
            resourceId = resourceId,
            parentId = parentId,
            expectedParentVersion = parentVersion,
            expectedChildVersion = childVersion,
        )

        assertSoftly { softly ->
            softly.assertThat(restored.status).isEqualTo("RESTORED")
            softly.assertThat(deleted(childTable, resourceId)).isFalse()
            softly.assertThat(version(childTable, resourceId)).isEqualTo(childVersion + 1)
            softly.assertThat(version(parentTable, parentId)).isEqualTo(parentVersion + 1)
            softly.assertThat(childTrashStatus(type, resourceId)).isEqualTo("RESTORED")
        }
    }

    private fun version(table: String, id: Long): Long = jdbcClient.sql(
        "SELECT version FROM $table WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getLong("version") }.single()

    private fun deleted(table: String, id: Long): Boolean = jdbcClient.sql(
        "SELECT deleted_at IS NOT NULL FROM $table WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getBoolean(1) }.single()

    private fun childTrashStatus(type: AdminChildTrashType, resourceId: Long): String = jdbcClient.sql(
        """
        SELECT status FROM admin_child_trash_records
        WHERE resource_type = :resourceType AND resource_id = :resourceId
        ORDER BY id DESC LIMIT 1
        """.trimIndent(),
    )
        .param("resourceType", type.name)
        .param("resourceId", resourceId)
        .query { rs, _ -> rs.getString("status") }
        .single()
}
