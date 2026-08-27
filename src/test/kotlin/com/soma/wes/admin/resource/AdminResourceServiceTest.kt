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
import java.time.OffsetDateTime
import java.time.ZonedDateTime

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

    @Test
    fun `사진 상세는 기술 품질 결과를 구조화해 읽기 전용으로 노출한다`() {
        val actor = adminAccountFixture.관리자("photo-quality-owner")
        val galleryId = createGallery(actor.requiredId, "photo-quality")
        val photo = service.create(
            actor.requiredId,
            AdminResourceType.PHOTO,
            CreateAdminResourceRequest(
                "기술 품질 확인 사진",
                mapOf(
                    "galleryId" to galleryId,
                    "storageKey" to "galleries/$galleryId/quality.jpg",
                    "originalFileName" to "quality.jpg",
                    "contentType" to "image/jpeg",
                ),
            ),
            "127.0.0.1",
        )
        val analyzedAt = OffsetDateTime.parse("2026-08-27T01:02:03Z")
        jdbcClient.sql(
            """
            UPDATE photos
            SET technical_quality_score = 87.25,
                technical_quality_signals = '{"algorithmVersion":"technical-v1","sharpnessScore":91.5}'::JSONB,
                quality_analyzed_at = :analyzedAt
            WHERE id = :photoId
            """.trimIndent(),
        )
            .param("analyzedAt", analyzedAt)
            .param("photoId", photo.id)
            .update()

        val detail = service.get(AdminResourceType.PHOTO, photo.id)
        assertThat((detail.fields["technicalQualityScore"] as Number).toDouble()).isEqualTo(87.25)
        assertThat(detail.fields["technicalQualityAnalyzedAt"]).isEqualTo(analyzedAt)
        val signals = detail.fields["technicalQualitySignals"] as Map<*, *>
        assertThat(signals["algorithmVersion"]).isEqualTo("technical-v1")
        assertThat(signals["sharpnessScore"]).isEqualTo(91.5)

        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.PHOTO,
                photo.id,
                UpdateAdminResourceRequest(
                    "기술 품질 수동 위조 차단",
                    photo.version,
                    mapOf("technicalQualityScore" to 100),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    @Test
    fun `셀렉 생성은 SELECTING으로 고정하고 제출은 리비전 워크플로만 허용한다`() {
        val actor = adminAccountFixture.관리자("selection-state-owner")
        val galleryId = createGallery(actor.requiredId, "selection-state")

        assertThatThrownBy {
            service.create(
                actor.requiredId,
                AdminResourceType.SELECTION,
                CreateAdminResourceRequest(
                    "제출 상태 우회 시도",
                    mapOf("galleryId" to galleryId, "status" to "SUBMITTED"),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val selection = service.create(
            actor.requiredId,
            AdminResourceType.SELECTION,
            CreateAdminResourceRequest("셀렉 생성", mapOf("galleryId" to galleryId)),
            "127.0.0.1",
        )
        assertThat(selection.fields["status"]).isEqualTo("SELECTING")
        assertThat(selection.fields["submittedAt"]).isNull()
        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.SELECTION,
                selection.id,
                UpdateAdminResourceRequest("직접 제출 우회", selection.version, mapOf("status" to "SUBMITTED")),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    @Test
    fun `보정 회차 전이는 상태와 요청 완료 시각을 원자적으로 맞춘다`() {
        val actor = adminAccountFixture.관리자("retouch-state-owner")
        val galleryId = createGallery(actor.requiredId, "retouch-state")

        assertThatThrownBy {
            service.create(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                CreateAdminResourceRequest(
                    "완료 상태 우회 시도",
                    mapOf("galleryId" to galleryId, "roundNo" to 1, "status" to "COMPLETED"),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val round = service.create(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            CreateAdminResourceRequest("보정 회차 생성", mapOf("galleryId" to galleryId, "roundNo" to 1)),
            "127.0.0.1",
        )
        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                round.id,
                UpdateAdminResourceRequest("완료 직행 차단", round.version, mapOf("status" to "COMPLETED")),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val requested = service.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            round.id,
            UpdateAdminResourceRequest("보정 요청 제출", round.version, mapOf("status" to "REQUESTED")),
            "127.0.0.1",
        )
        assertThat(requested.fields["status"]).isEqualTo("REQUESTED")
        assertThat(requested.fields["requestedAt"]).isNotNull()
        assertThat(requested.fields["completedAt"]).isNull()

        val completed = service.update(
            actor.requiredId,
            AdminResourceType.RETOUCH_REQUEST,
            round.id,
            UpdateAdminResourceRequest("보정 완료", requested.version, mapOf("status" to "COMPLETED")),
            "127.0.0.1",
        )
        assertThat(completed.fields["status"]).isEqualTo("COMPLETED")
        assertThat(completed.fields["requestedAt"]).isNotNull()
        assertThat(completed.fields["completedAt"]).isNotNull()
        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.RETOUCH_REQUEST,
                round.id,
                UpdateAdminResourceRequest(
                    "완료 시각 직접 삭제 차단",
                    completed.version,
                    mapOf("completedAt" to null),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    @Test
    fun `스튜디오 생성은 미확정 소유자를 사진작가로 확정하고 CLIENT 소유자는 거절한다`() {
        val actor = adminAccountFixture.관리자("studio-owner-type-policy")
        val untypedOwner = createUser(actor.requiredId, "studio-untyped-owner")
        val ownerRevisionCountBefore = userRevisionCount(untypedOwner.id)
        val ownerAuditCountBefore = userAuditCount(untypedOwner.id)

        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "미확정 사용자의 스튜디오 생성",
                mapOf("userId" to untypedOwner.id, "name" to "타입 확정 스튜디오", "galleryUrl" to "typed-owner"),
            ),
            "127.0.0.1",
        )
        val confirmedOwner = service.get(AdminResourceType.USER, untypedOwner.id)
        assertThat(studio.fields["userId"]).isEqualTo(untypedOwner.id)
        assertThat(confirmedOwner.fields["userType"]).isEqualTo("PHOTOGRAPHER")
        assertThat(confirmedOwner.version).isEqualTo(untypedOwner.version + 1)
        assertThat(userRevisionCount(untypedOwner.id)).isEqualTo(ownerRevisionCountBefore + 1)
        assertThat(userAuditCount(untypedOwner.id)).isEqualTo(ownerAuditCountBefore + 1)
        val typeRevision = jdbcClient.sql(
            """
            SELECT before_snapshot, after_snapshot, target_version
            FROM admin_entity_revisions
            WHERE target_type = 'USER' AND target_id = :targetId
            ORDER BY revision_number DESC
            LIMIT 1
            """.trimIndent(),
        )
            .param("targetId", untypedOwner.id.toString())
            .query { rs, _ -> Triple(
                rs.getString("before_snapshot"),
                rs.getString("after_snapshot"),
                rs.getLong("target_version"),
            ) }
            .single()
        assertThat(typeRevision.first).contains("\"version\":0", "\"userType\":null")
        assertThat(typeRevision.second).contains("\"version\":1", "\"userType\":\"PHOTOGRAPHER\"")
        assertThat(typeRevision.third).isEqualTo(1L)
        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.USER,
                untypedOwner.id,
                UpdateAdminResourceRequest(
                    "확정 타입 직접 변경 차단",
                    confirmedOwner.version,
                    mapOf("userType" to "CLIENT"),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val ownerAfterRejectedChange = service.get(AdminResourceType.USER, untypedOwner.id)
        assertThat(ownerAfterRejectedChange.fields["userType"]).isEqualTo("PHOTOGRAPHER")
        assertThat(ownerAfterRejectedChange.version).isEqualTo(confirmedOwner.version)
        assertThat(userRevisionCount(untypedOwner.id)).isEqualTo(ownerRevisionCountBefore + 1)
        assertThat(userAuditCount(untypedOwner.id)).isEqualTo(ownerAuditCountBefore + 1)

        val clientOwner = service.create(
            actor.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest(
                "CLIENT 사용자 생성",
                mapOf(
                    "provider" to "GOOGLE",
                    "providerId" to "studio-client-owner",
                    "nickname" to "CLIENT 소유자",
                    "userType" to "CLIENT",
                ),
            ),
            "127.0.0.1",
        )
        val clientRevisionCountBefore = userRevisionCount(clientOwner.id)
        val clientAuditCountBefore = userAuditCount(clientOwner.id)
        assertThatThrownBy {
            service.create(
                actor.requiredId,
                AdminResourceType.STUDIO,
                CreateAdminResourceRequest(
                    "CLIENT 스튜디오 우회 시도",
                    mapOf("userId" to clientOwner.id, "name" to "차단 대상", "galleryUrl" to "client-owner-blocked"),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        assertThat(service.get(AdminResourceType.USER, clientOwner.id).fields["userType"]).isEqualTo("CLIENT")
        assertThat(userRevisionCount(clientOwner.id)).isEqualTo(clientRevisionCountBefore)
        assertThat(userAuditCount(clientOwner.id)).isEqualTo(clientAuditCountBefore)
        assertThat(
            jdbcClient.sql("SELECT COUNT(*) FROM studios WHERE gallery_url = 'client-owner-blocked'")
                .query { rs, _ -> rs.getLong(1) }.single(),
        ).isZero()
    }

    @Test
    fun `갤러리 상태와 운영 상태와 마감은 일반 CRUD가 아니라 전용 workflow만 변경한다`() {
        val actor = adminAccountFixture.관리자("gallery-state-policy")
        val user = createUser(actor.requiredId, "gallery-state-owner")
        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "상태 정책 스튜디오",
                mapOf("userId" to user.id, "name" to "상태 정책", "galleryUrl" to "gallery-state-policy"),
            ),
            "127.0.0.1",
        )

        assertThatThrownBy {
            service.create(
                actor.requiredId,
                AdminResourceType.GALLERY,
                CreateAdminResourceRequest(
                    "OPEN 생성 우회",
                    mapOf(
                        "studioId" to studio.id,
                        "title" to "우회 갤러리",
                        "status" to "OPEN",
                        "workflowStatus" to "IN_PROGRESS",
                        "selectionDeadline" to ZonedDateTime.now().plusDays(7).toOffsetDateTime().toString(),
                    ),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "DRAFT 갤러리 생성",
                mapOf("studioId" to studio.id, "title" to "정상 갤러리"),
            ),
            "127.0.0.1",
        )
        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.GALLERY,
                gallery.id,
                UpdateAdminResourceRequest(
                    "일반 수정으로 상태 우회",
                    gallery.version,
                    mapOf(
                        "status" to "CLOSED",
                        "workflowStatus" to "COMPLETED",
                        "selectionDeadline" to ZonedDateTime.now().plusDays(14).toOffsetDateTime().toString(),
                    ),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val unchanged = service.get(AdminResourceType.GALLERY, gallery.id)
        assertThat(unchanged.version).isEqualTo(gallery.version)
        assertThat(unchanged.fields)
            .containsEntry("status", "DRAFT")
            .containsEntry("workflowStatus", "DRAFT")
            .containsEntry("selectionDeadline", null)
    }

    private fun createGallery(actorAdminId: Long, slug: String): Long {
        val user = createUser(actorAdminId, "$slug-user")
        val studio = service.create(
            actorAdminId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "테스트 스튜디오 생성",
                mapOf("userId" to user.id, "name" to slug, "galleryUrl" to slug),
            ),
            "127.0.0.1",
        )
        return service.create(
            actorAdminId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest("테스트 갤러리 생성", mapOf("studioId" to studio.id, "title" to slug)),
            "127.0.0.1",
        ).id
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
}
