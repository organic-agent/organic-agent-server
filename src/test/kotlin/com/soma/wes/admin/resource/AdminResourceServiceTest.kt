package com.soma.wes.admin.resource

import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

@IntegrationTest
class AdminResourceServiceTest @Autowired constructor(
    private val service: AdminResourceService,
    private val adminAccountFixture: AdminAccountFixture,
    private val auditLogRepository: AdminAuditLogRepository,
    private val jdbcClient: JdbcClient,
) {

    @Test
    fun `허용한 필드만 생성 수정하며 비밀값과 감사 스냅샷을 마스킹한다`() {
        val actor = adminAccountFixture.관리자("resource-owner")

        val created = service.create(
            actorAdminId = actor.requiredId,
            type = AdminResourceType.USER,
            request = CreateAdminResourceRequest(
                reason = "지원 요청으로 사용자 생성",
                fields = mapOf(
                    "provider" to "google",
                    "providerId" to "provider-secret-value",
                    "nickname" to "문제 해결 사용자",
                    "email" to "private@example.com",
                ),
            ),
            sourceAddress = "127.0.0.1",
        )

        assertThat(created.version).isZero()
        assertThat(created.fields["providerId"]).isEqualTo("[MASKED]")
        assertThat(created.fields["email"]).isEqualTo("private@example.com")

        val updated = service.update(
            actorAdminId = actor.requiredId,
            type = AdminResourceType.USER,
            id = created.id,
            request = UpdateAdminResourceRequest(
                reason = "닉네임 정정",
                expectedVersion = created.version,
                fields = mapOf("nickname" to "정정된 사용자"),
            ),
            sourceAddress = "127.0.0.1",
        )

        assertThat(updated.version).isEqualTo(1)
        assertThat(updated.label).isEqualTo("정정된 사용자")
        assertThat(auditLogRepository.findAll()).hasSize(2)

        val revisions = jdbcClient.sql(
            "SELECT before_snapshot, after_snapshot FROM admin_entity_revisions ORDER BY id",
        ).query { rs, _ -> listOf(rs.getString(1), rs.getString(2)) }.list().flatten().filterNotNull()
        assertThat(revisions.joinToString()).doesNotContain("provider-secret-value", "private@example.com")
        assertThat(revisions.joinToString()).contains("[REDACTED]")
    }

    @Test
    fun `오래된 expectedVersion 변경은 감사 이력 없이 차단한다`() {
        val actor = adminAccountFixture.관리자("version-owner")
        val user = service.create(
            actor.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "충돌 테스트 사용자 생성",
                mapOf("provider" to "KAKAO", "providerId" to "v-1", "nickname" to "버전 사용자"),
            ),
            "127.0.0.1",
        )
        service.update(
            actor.requiredId,
            AdminResourceType.USER,
            user.id,
            UpdateAdminResourceRequest("첫 변경", user.version, mapOf("nickname" to "첫 변경 완료")),
            "127.0.0.1",
        )
        val auditCount = auditLogRepository.count()

        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.USER,
                user.id,
                UpdateAdminResourceRequest("오래된 변경", user.version, mapOf("nickname" to "덮어쓰기")),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        }
        assertThat(auditLogRepository.count()).isEqualTo(auditCount)
    }

    @Test
    fun `휴지통을 지원하지 않는 리소스는 물리 삭제하지 않는다`() {
        val actor = adminAccountFixture.관리자("delete-guard-owner")
        val user = createUser(actor.requiredId, "delete-guard-user")
        val auditCount = auditLogRepository.count()

        assertThatThrownBy {
            service.delete(
                actor.requiredId,
                AdminResourceType.USER,
                user.id,
                ChangeAdminResourceStateRequest("지원 요청 종료", user.version),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.RESOURCE_DELETE_UNSUPPORTED)
        }

        assertThat(service.get(AdminResourceType.USER, user.id).id).isEqualTo(user.id)
        assertThat(auditLogRepository.count()).isEqualTo(auditCount)
    }

    @Test
    fun `갤러리 삭제는 휴지통으로 보내고 새 버전으로 복원한다`() {
        val actor = adminAccountFixture.관리자("restore-owner")
        val user = createUser(actor.requiredId, "restore-user")
        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "복원 테스트 스튜디오",
                mapOf("userId" to user.id, "name" to "복원 스튜디오", "galleryUrl" to "restore-studio"),
            ),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "복원 테스트 갤러리",
                mapOf("studioId" to studio.id, "title" to "복원 갤러리"),
            ),
            "127.0.0.1",
        )

        service.delete(
            actor.requiredId,
            AdminResourceType.GALLERY,
            gallery.id,
            ChangeAdminResourceStateRequest("실수로 생성", gallery.version),
            "127.0.0.1",
        )
        val deleted = service.get(AdminResourceType.GALLERY, gallery.id)
        assertThat(deleted.deleted).isTrue()
        assertThat(deleted.version).isEqualTo(1)

        val restored = service.restore(
            actor.requiredId,
            AdminResourceType.GALLERY,
            gallery.id,
            ChangeAdminResourceStateRequest("삭제 취소", deleted.version),
            "127.0.0.1",
        )
        assertThat(restored.deleted).isFalse()
        assertThat(restored.version).isEqualTo(2)
    }

    @Test
    fun `협업 링크 원문은 생성 응답과 상세 응답에 나오지 않는다`() {
        val actor = adminAccountFixture.관리자("collab-owner")
        val user = createUser(actor.requiredId, "collab-user")
        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest("협업 스튜디오", mapOf("userId" to user.id, "name" to "협업", "galleryUrl" to "collab-studio")),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest("협업 갤러리", mapOf("studioId" to studio.id, "title" to "협업 갤러리")),
            "127.0.0.1",
        )

        val collaboration = service.create(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            CreateAdminResourceRequest("지원용 링크 생성", mapOf("galleryId" to gallery.id, "name" to "가족 의견")),
            "127.0.0.1",
        )

        assertThat(collaboration.fields["collabToken"]).isEqualTo("[MASKED]")
        assertThat(service.get(AdminResourceType.COLLABORATION, collaboration.id).fields["collabToken"])
            .isEqualTo("[MASKED]")
        val raw = jdbcClient.sql("SELECT collab_token FROM collab_sessions WHERE id = :id")
            .param("id", collaboration.id)
            .query { rs, _ -> rs.getString(1) }
            .single()
        assertThat(raw).isNotBlank().isNotEqualTo("[MASKED]")
    }

    @Test
    fun `현재 비즈니스 리소스 여덟 종류를 생성하고 통합 검색한다`() {
        val actor = adminAccountFixture.관리자("all-resource-owner")
        val user = createUser(actor.requiredId, "all-resource-user")
        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest("전체 리소스 스튜디오", mapOf("userId" to user.id, "name" to "전체 리소스", "galleryUrl" to "all-resource")),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest("전체 리소스 갤러리", mapOf("studioId" to studio.id, "title" to "전체 리소스 갤러리")),
            "127.0.0.1",
        )
        val photo = service.create(
            actor.requiredId,
            AdminResourceType.PHOTO,
            CreateAdminResourceRequest(
                "운영 복구 사진 등록",
                mapOf(
                    "galleryId" to gallery.id,
                    "storageKey" to "galleries/${gallery.id}/all-resource.jpg",
                    "originalFileName" to "전체 리소스.jpg",
                    "contentType" to "image/jpeg",
                ),
            ),
            "127.0.0.1",
        )
        val selection = service.create(
            actor.requiredId,
            AdminResourceType.SELECTION,
            CreateAdminResourceRequest("셀렉 앨범 복구", mapOf("galleryId" to gallery.id)),
            "127.0.0.1",
        )
        val collaboration = service.create(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            CreateAdminResourceRequest("협업 복구", mapOf("galleryId" to gallery.id, "name" to "전체 리소스 협업")),
            "127.0.0.1",
        )
        val album = service.create(
            actor.requiredId,
            AdminResourceType.ALBUM,
            CreateAdminResourceRequest("앨범 복구", mapOf("galleryId" to gallery.id, "name" to "전체 리소스 앨범")),
            "127.0.0.1",
        )
        val retouch = service.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest("보정 요청 복구", mapOf("galleryId" to gallery.id, "roundNo" to 1)),
            "127.0.0.1",
        )

        assertThat(photo.fields["storageKey"]).isEqualTo("[MASKED]")
        assertThat(selection.fields["status"]).isEqualTo("SELECTING")
        assertThat(collaboration.fields["collabToken"]).isEqualTo("[MASKED]")
        assertThat(album.fields["name"]).isEqualTo("전체 리소스 앨범")
        assertThat(retouch.fields["status"]).isEqualTo("DRAFTING")

        val found = service.search("전체 리소스", emptySet(), page = 0, size = 100)
        assertThat(found.contents.map { it.type })
            .contains(
                AdminResourceType.STUDIO,
                AdminResourceType.GALLERY,
                AdminResourceType.PHOTO,
                AdminResourceType.COLLABORATION,
                AdminResourceType.ALBUM,
            )
    }

    private fun createUser(actorAdminId: Long, providerId: String) =
        service.create(
            actorAdminId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "테스트 사용자 생성",
                mapOf("provider" to "GOOGLE", "providerId" to providerId, "nickname" to providerId),
            ),
            "127.0.0.1",
        )
}
