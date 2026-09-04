package com.soma.wes.admin.resource

import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminCascadeTrashService
import com.soma.wes.admin.resource.service.AdminResourceContextService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.IntegrationTest
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

@IntegrationTest
class AdminResourceServiceTest @Autowired constructor(
    private val service: AdminResourceService,
    private val contextService: AdminResourceContextService,
    private val cascadeTrashService: AdminCascadeTrashService,
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
                mapOf("ownerUserId" to user.id, "name" to "복원 스튜디오", "galleryUrl" to "restore-studio"),
            ),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "복원 테스트 갤러리",
                mapOf("workspaceId" to studio.id, "title" to "복원 갤러리"),
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
            CreateAdminResourceRequest("협업 스튜디오", mapOf("ownerUserId" to user.id, "name" to "협업", "galleryUrl" to "collab-studio")),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest("협업 갤러리", mapOf("workspaceId" to studio.id, "title" to "협업 갤러리")),
            "127.0.0.1",
        )
        val conceptFolderId = createConceptFolder(gallery.id, "가족")

        val collaboration = service.create(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            CreateAdminResourceRequest(
                "지원용 링크 생성",
                mapOf("galleryId" to gallery.id, "conceptFolderId" to conceptFolderId, "name" to "가족 의견"),
            ),
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
    fun `현재 비즈니스 리소스를 생성하고 통합 검색한다`() {
        val actor = adminAccountFixture.관리자("all-resource-owner")
        val user = createUser(actor.requiredId, "all-resource-user")
        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest("전체 리소스 스튜디오", mapOf("ownerUserId" to user.id, "name" to "전체 리소스", "galleryUrl" to "all-resource")),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest("전체 리소스 갤러리", mapOf("workspaceId" to studio.id, "title" to "전체 리소스 갤러리")),
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
        val selectionId = jdbcClient.sql("SELECT id FROM photo_selections WHERE gallery_id = :galleryId")
            .param("galleryId", gallery.id).query { rs, _ -> rs.getLong(1) }.single()
        val selection = service.get(AdminResourceType.SELECTION, selectionId)
        val conceptFolderId = createConceptFolder(gallery.id, "전체 리소스")
        val collaboration = service.create(
            actor.requiredId,
            AdminResourceType.COLLABORATION,
            CreateAdminResourceRequest(
                "협업 복구",
                mapOf("galleryId" to gallery.id, "conceptFolderId" to conceptFolderId, "name" to "전체 리소스 협업"),
            ),
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
        assertThat(retouch.fields["status"]).isEqualTo("DRAFTING")

        val found = service.search("전체 리소스", emptySet(), page = 0, size = 100)
        assertThat(found.contents.map { it.type })
            .contains(
                AdminResourceType.STUDIO,
                AdminResourceType.GALLERY,
                AdminResourceType.PHOTO,
                AdminResourceType.COLLABORATION,
            )
    }

    @Test
    fun `워크스페이스 카테고리 분류 작업 평점은 새 관리자 리소스 계약으로 조회 수정 삭제한다`() {
        val actor = adminAccountFixture.관리자("new-schema-resource-owner")
        val owner = createUser(actor.requiredId, "new-schema-owner")
        val personalWorkspaceId = jdbcClient.sql(
            "SELECT id FROM workspaces WHERE type = 'PERSONAL' AND personal_owner_user_id = :userId",
        ).param("userId", owner.id).query { rs, _ -> rs.getLong(1) }.single()
        val workspace = service.get(AdminResourceType.WORKSPACE, personalWorkspaceId)
        assertThat(workspace.fields)
            .containsEntry("type", "PERSONAL")
            .doesNotContainKeys("role", "userType")
        assertThat(owner.fields).doesNotContainKeys("role", "userType")
        assertThat(
            jdbcClient.sql("SELECT role FROM users WHERE id = :id")
                .param("id", owner.id).query { rs, _ -> rs.getString(1) }.single(),
        ).isEqualTo("USER")
        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.USER,
                owner.id,
                UpdateAdminResourceRequest("전역 역할 수정 차단", owner.version, mapOf("role" to "ADMIN")),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "새 스키마 스튜디오",
                mapOf("ownerUserId" to owner.id, "name" to "새 스키마", "galleryUrl" to "new-schema"),
            ),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "새 스키마 갤러리",
                mapOf("workspaceId" to studio.id, "createdByUserId" to owner.id, "title" to "분류 갤러리"),
            ),
            "127.0.0.1",
        )
        val photo = service.create(
            actor.requiredId,
            AdminResourceType.PHOTO,
            CreateAdminResourceRequest(
                "분류 사진",
                mapOf(
                    "galleryId" to gallery.id,
                    "storageKey" to "galleries/${gallery.id}/category.jpg",
                    "originalFileName" to "category.jpg",
                    "contentType" to "image/jpeg",
                ),
            ),
            "127.0.0.1",
        )
        val concept = service.create(
            actor.requiredId,
            AdminResourceType.CONCEPT_FOLDER,
            CreateAdminResourceRequest(
                "컨셉 폴더 생성",
                mapOf("galleryId" to gallery.id, "name" to "가족", "sortOrder" to 0),
            ),
            "127.0.0.1",
        )
        val firstDetail = service.create(
            actor.requiredId,
            AdminResourceType.DETAIL_FOLDER,
            CreateAdminResourceRequest(
                "세부 폴더 생성",
                mapOf("conceptFolderId" to concept.id, "name" to "부모님", "sortOrder" to 0),
            ),
            "127.0.0.1",
        )
        val secondDetail = service.create(
            actor.requiredId,
            AdminResourceType.DETAIL_FOLDER,
            CreateAdminResourceRequest(
                "대체 세부 폴더 생성",
                mapOf("conceptFolderId" to concept.id, "name" to "형제", "sortOrder" to 1),
            ),
            "127.0.0.1",
        )
        val assignment = service.create(
            actor.requiredId,
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
            CreateAdminResourceRequest(
                "사진 수동 분류",
                mapOf(
                    "photoId" to photo.id,
                    "detailFolderId" to firstDetail.id,
                    "assignedByUserId" to owner.id,
                ),
            ),
            "127.0.0.1",
        )
        val rating = service.create(
            actor.requiredId,
            AdminResourceType.PHOTO_RATING,
            CreateAdminResourceRequest(
                "사진 평점",
                mapOf("photoId" to photo.id, "score" to 4, "ratedByUserId" to owner.id),
            ),
            "127.0.0.1",
        )
        val jobId = jdbcClient.sql(
            """
            INSERT INTO categorization_jobs
                (gallery_id, mode, status, started_at, completed_at, version, created_at, updated_at)
            VALUES (:galleryId, 'INITIAL', 'SUCCEEDED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
        ).param("galleryId", gallery.id).query { rs, _ -> rs.getLong(1) }.single()
        jdbcClient.sql(
            """
            INSERT INTO categorization_job_photos (job_id, gallery_id, photo_id, status, processed_at)
            VALUES (:jobId, :galleryId, :photoId, 'ASSIGNED', CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("jobId", jobId).param("galleryId", gallery.id).param("photoId", photo.id).update()
        val selectionId = jdbcClient.sql("SELECT id FROM photo_selections WHERE gallery_id = :galleryId")
            .param("galleryId", gallery.id).query { rs, _ -> rs.getLong(1) }.single()
        jdbcClient.sql(
            """
            INSERT INTO photo_selection_items
                (selection_id, gallery_id, photo_id, added_by_user_id, sort_order, version, created_at, updated_at)
            VALUES (:selectionId, :galleryId, :photoId, :userId, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("selectionId", selectionId)
            .param("galleryId", gallery.id)
            .param("photoId", photo.id)
            .param("userId", owner.id)
            .update()

        assertThat(assignment.id).isEqualTo(photo.id)
        assertThat(rating.id).isEqualTo(photo.id)
        assertThat(service.get(AdminResourceType.CATEGORIZATION_JOB, jobId).fields["status"])
            .isEqualTo("SUCCEEDED")
        assertThat(service.search("부모님", setOf(AdminResourceType.DETAIL_FOLDER), 0, 20).contents.map { it.id })
            .containsExactly(firstDetail.id)

        val workspaceContext = contextService.get(AdminResourceType.WORKSPACE, studio.id)
        assertThat(workspaceContext.facts)
            .containsEntry("workspaceType", "STUDIO")
            .containsEntry("galleries", 1L)
        assertThat(workspaceContext.sections.getValue("members").single())
            .containsEntry("accessRole", "OWNER")
        assertThat(workspaceContext.sections.getValue("galleries").single())
            .containsEntry("stage", "UPLOAD")
        assertThat(contextService.get(AdminResourceType.STUDIO, studio.id).sections.getValue("galleries").single())
            .containsEntry("stage", "UPLOAD")
        val galleryContext = contextService.get(AdminResourceType.GALLERY, gallery.id)
        assertThat(galleryContext.facts)
            .containsEntry("publicStatus", "DRAFT")
            .containsEntry("workflowStatus", "DRAFT")
            .containsEntry("stage", "UPLOAD")
            .containsEntry("categoryAssignments", 1L)
            .containsEntry("categorizationJobs", 1L)
            .containsEntry("ratings", 1L)
        assertThat(galleryContext.sections).containsKeys(
            "conceptFolders", "detailFolders", "categoryAssignments", "categorizationJobs", "photoRatings",
            "userNotifications", "userNotificationSettings",
        )
        val photoContext = contextService.get(AdminResourceType.PHOTO, photo.id)
        assertThat(photoContext.sections).containsKeys("categoryAssignment", "categorizationJobs", "rating")
        val jobPhotoRow = contextService.get(AdminResourceType.CATEGORIZATION_JOB, jobId)
            .sections.getValue("photos").single()
        assertThat(jobPhotoRow)
            .containsEntry("photoId", photo.id)
            .containsEntry("galleryId", gallery.id)
            .containsEntry("photoStatus", "PENDING")
            .containsEntry("categorizationStatus", "ASSIGNED")
            .containsEntry("failureCode", null)
        assertThat(jobPhotoRow["processedAt"]).isNotNull()
        val selectionItemRow = contextService.get(AdminResourceType.SELECTION, selectionId)
            .sections.getValue("items").single()
        assertThat(selectionItemRow)
            .containsEntry("galleryId", gallery.id)
            .containsEntry("photoId", photo.id)
            .containsEntry("addedByUserId", owner.id)
            .containsEntry("sortOrder", 0)

        val moved = service.update(
            actor.requiredId,
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
            assignment.id,
            UpdateAdminResourceRequest(
                "분류 이동",
                assignment.version,
                mapOf("detailFolderId" to secondDetail.id, "assignedByUserId" to owner.id),
            ),
            "127.0.0.1",
        )
        assertThat(moved.fields)
            .containsEntry("detailFolderId", secondDetail.id)
            .containsEntry("assignedSource", "USER")
            .containsEntry("confidence", null)
        val rerated = service.update(
            actor.requiredId,
            AdminResourceType.PHOTO_RATING,
            rating.id,
            UpdateAdminResourceRequest("평점 수정", rating.version, mapOf("score" to 5)),
            "127.0.0.1",
        )
        assertThat(rerated.fields["score"]).isEqualTo(5)

        listOf(AdminResourceType.WORKSPACE to studio.id, AdminResourceType.CATEGORIZATION_JOB to jobId)
            .forEach { (type, id) ->
                assertThatThrownBy {
                    cascadeTrashService.delete(
                        actor.requiredId,
                        type,
                        id,
                        ChangeAdminResourceStateRequest("지원하지 않는 삭제", service.get(type, id).version),
                        "127.0.0.1",
                    )
                }.isInstanceOfSatisfying(AdminException::class.java) {
                    assertThat(it.errorCode).isEqualTo(AdminErrorCode.RESOURCE_DELETE_UNSUPPORTED)
                }
            }
        assertThatThrownBy {
            service.create(
                actor.requiredId,
                AdminResourceType.CATEGORIZATION_JOB,
                CreateAdminResourceRequest("직접 작업 생성 차단", emptyMap()),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }

        service.delete(
            actor.requiredId,
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
            moved.id,
            ChangeAdminResourceStateRequest("분류 제거", moved.version),
            "127.0.0.1",
        )
        service.delete(
            actor.requiredId,
            AdminResourceType.PHOTO_RATING,
            rerated.id,
            ChangeAdminResourceStateRequest("평점 제거", rerated.version),
            "127.0.0.1",
        )
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM photo_category_assignments WHERE photo_id = :id")
            .param("id", photo.id).query { rs, _ -> rs.getLong(1) }.single()).isZero()
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM photo_ratings WHERE photo_id = :id")
            .param("id", photo.id).query { rs, _ -> rs.getLong(1) }.single()).isZero()
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
    fun `셀렉은 갤러리 생성과 함께 만들어지고 제출은 리비전 워크플로만 허용한다`() {
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

        val selectionId = jdbcClient.sql("SELECT id FROM photo_selections WHERE gallery_id = :galleryId")
            .param("galleryId", galleryId).query { rs, _ -> rs.getLong(1) }.single()
        val selection = service.get(AdminResourceType.SELECTION, selectionId)
        assertThat(selection.fields["status"]).isEqualTo("SELECTING")
        assertThat(selection.fields["submittedAt"]).isNull()
        assertThatThrownBy {
            service.create(
                actor.requiredId,
                AdminResourceType.SELECTION,
                CreateAdminResourceRequest("중복 셀렉 생성", mapOf("galleryId" to galleryId)),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
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
    fun `스튜디오 생성은 STUDIO 워크스페이스와 소유자 멤버십을 함께 만든다`() {
        val actor = adminAccountFixture.관리자("studio-workspace-policy")
        val owner = createUser(actor.requiredId, "studio-workspace-owner")

        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "워크스페이스 기반 스튜디오 생성",
                mapOf("ownerUserId" to owner.id, "name" to "워크스페이스 스튜디오", "galleryUrl" to "workspace-owner"),
            ),
            "127.0.0.1",
        )

        assertThat(studio.id).isEqualTo(studio.fields["workspaceId"])
        assertThat(studio.fields["ownerUserId"]).isEqualTo(owner.id)
        assertThat(service.get(AdminResourceType.USER, owner.id).version).isEqualTo(owner.version)
        assertThat(
            jdbcClient.sql("SELECT type FROM workspaces WHERE id = :workspaceId")
                .param("workspaceId", studio.id)
                .query { rs, _ -> rs.getString(1) }
                .single(),
        ).isEqualTo("STUDIO")
        assertThat(
            jdbcClient.sql(
                "SELECT role FROM workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId AND deleted_at IS NULL",
            )
                .param("workspaceId", studio.id)
                .param("userId", owner.id)
                .query { rs, _ -> rs.getString(1) }
                .single(),
        ).isEqualTo("OWNER")
        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.USER,
                owner.id,
                UpdateAdminResourceRequest("구 사용자 유형 변경 차단", owner.version, mapOf("userType" to "CLIENT")),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
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
                mapOf("ownerUserId" to user.id, "name" to "상태 정책", "galleryUrl" to "gallery-state-policy"),
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
                        "workspaceId" to studio.id,
                        "title" to "우회 갤러리",
                        "status" to "OPEN",
                        "workflowStatus" to "IN_PROGRESS",
                        "stage" to "RETOUCH",
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
                mapOf("workspaceId" to studio.id, "title" to "정상 갤러리"),
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
                        "stage" to "DELIVERY",
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
            .containsEntry("stage", "UPLOAD")
            .containsEntry("selectionDeadline", null)
        assertThat(service.search("UPLOAD", setOf(AdminResourceType.GALLERY), 0, 20).contents.map { it.id })
            .contains(gallery.id)
    }

    @Test
    fun `스튜디오 연락처와 소개는 CRUD와 컨텍스트에 노출하고 과거 유입 경로는 조회 전용으로 둔다`() {
        val actor = adminAccountFixture.관리자("studio-profile-contract")
        val owner = createUser(actor.requiredId, "studio-profile-owner")
        assertThatThrownBy {
            service.create(
                actor.requiredId,
                AdminResourceType.STUDIO,
                CreateAdminResourceRequest(
                    "과거 유입 경로 생성 차단",
                    mapOf(
                        "ownerUserId" to owner.id,
                        "name" to "잘못된 스튜디오",
                        "galleryUrl" to "studio-profile-invalid-inflow",
                        "inflowChannel" to "ADMIN",
                    ),
                ),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "스튜디오 프로필 생성",
                mapOf(
                    "ownerUserId" to owner.id,
                    "name" to "프로필 스튜디오",
                    "galleryUrl" to "studio-profile-contract",
                    "contact" to "02-123-4567",
                    "description" to "웨딩 사진 전문",
                ),
            ),
            "127.0.0.1",
        )
        jdbcClient.sql("UPDATE studios SET inflow_channel = 'LEGACY_BLOG' WHERE workspace_id = :id")
            .param("id", studio.id).update()

        val detail = service.get(AdminResourceType.STUDIO, studio.id)
        assertThat(detail.fields)
            .containsEntry("contact", "02-123-4567")
            .containsEntry("description", "웨딩 사진 전문")
            .containsEntry("inflowChannel", "LEGACY_BLOG")
        assertThat(contextService.get(AdminResourceType.STUDIO, studio.id).resource.fields)
            .containsEntry("contact", "02-123-4567")
            .containsEntry("description", "웨딩 사진 전문")
        assertThat(contextService.get(AdminResourceType.WORKSPACE, studio.id).sections.getValue("studio").single())
            .containsEntry("contact", "02-123-4567")
            .containsEntry("description", "웨딩 사진 전문")

        val updated = service.update(
            actor.requiredId,
            AdminResourceType.STUDIO,
            studio.id,
            UpdateAdminResourceRequest(
                "스튜디오 프로필 수정",
                detail.version,
                mapOf("contact" to "010-9999-0000", "description" to "본식과 리허설 전문"),
            ),
            "127.0.0.1",
        )
        assertThat(updated.fields)
            .containsEntry("contact", "010-9999-0000")
            .containsEntry("description", "본식과 리허설 전문")
            .containsEntry("inflowChannel", "LEGACY_BLOG")

        assertThatThrownBy {
            service.update(
                actor.requiredId,
                AdminResourceType.STUDIO,
                studio.id,
                UpdateAdminResourceRequest("과거 유입 경로 수정 차단", updated.version, mapOf("inflowChannel" to "ADMIN")),
                "127.0.0.1",
            )
        }.isInstanceOfSatisfying(AdminException::class.java) {
            assertThat(it.errorCode).isEqualTo(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
    }

    @Test
    fun `사용자 알림과 설정은 사용자와 관련 스튜디오 갤러리 컨텍스트에서 읽기만 한다`() {
        val actor = adminAccountFixture.관리자("user-notification-context")
        val owner = createUser(actor.requiredId, "notification-owner")
        val studio = service.create(
            actor.requiredId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "알림 컨텍스트 스튜디오",
                mapOf("ownerUserId" to owner.id, "name" to "알림 스튜디오", "galleryUrl" to "notification-context"),
            ),
            "127.0.0.1",
        )
        val gallery = service.create(
            actor.requiredId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest(
                "알림 컨텍스트 갤러리",
                mapOf("workspaceId" to studio.id, "title" to "알림 갤러리"),
            ),
            "127.0.0.1",
        )
        jdbcClient.sql(
            """
            INSERT INTO user_notification_settings
                (user_id, email_enabled, browser_enabled, version, created_at, updated_at)
            VALUES (:userId, FALSE, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("userId", owner.id).update()
        jdbcClient.sql(
            """
            INSERT INTO user_notifications
                (user_id, type, scope, scope_id, title, message, version, created_at, updated_at)
            VALUES
                (:userId, 'WORKSPACE_DELETED', 'STUDIO', :studioId, '스튜디오 알림', '스튜디오 메시지', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (:userId, 'SELECTION_SUBMITTED', 'GALLERY', :galleryId, '갤러리 알림', '갤러리 메시지', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("userId", owner.id).param("studioId", studio.id).param("galleryId", gallery.id).update()

        val beforeCount = jdbcClient.sql("SELECT COUNT(*) FROM user_notifications")
            .query { rs, _ -> rs.getLong(1) }.single()
        val userContext = contextService.get(AdminResourceType.USER, owner.id)
        val studioContext = contextService.get(AdminResourceType.STUDIO, studio.id)
        val galleryContext = contextService.get(AdminResourceType.GALLERY, gallery.id)

        assertThat(userContext.sections.getValue("userNotifications")).hasSize(2)
        assertThat(userContext.sections.getValue("userNotificationSettings").single())
            .containsEntry("emailEnabled", false)
            .containsEntry("browserEnabled", true)
            .containsEntry("settingsPersisted", true)
        assertThat(studioContext.sections.getValue("userNotifications").single())
            .containsEntry("scope", "STUDIO")
        assertThat(galleryContext.sections.getValue("userNotifications").single())
            .containsEntry("scope", "GALLERY")
        assertThat(galleryContext.sections.getValue("userNotificationSettings").single())
            .containsEntry("userId", owner.id)
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM user_notifications").query { rs, _ -> rs.getLong(1) }.single())
            .isEqualTo(beforeCount)
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM admin_notification_inbox").query { rs, _ -> rs.getLong(1) }.single())
            .isZero()

        service.update(
            actor.requiredId,
            AdminResourceType.GALLERY,
            gallery.id,
            UpdateAdminResourceRequest("관리자 제목 정정", gallery.version, mapOf("title" to "정정된 알림 갤러리")),
            "127.0.0.1",
        )
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM user_notifications").query { rs, _ -> rs.getLong(1) }.single())
            .isEqualTo(beforeCount)
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM admin_notification_inbox").query { rs, _ -> rs.getLong(1) }.single())
            .isZero()
    }

    private fun createGallery(actorAdminId: Long, slug: String): Long {
        val user = createUser(actorAdminId, "$slug-user")
        val studio = service.create(
            actorAdminId,
            AdminResourceType.STUDIO,
            CreateAdminResourceRequest(
                "테스트 스튜디오 생성",
                mapOf("ownerUserId" to user.id, "name" to slug, "galleryUrl" to slug),
            ),
            "127.0.0.1",
        )
        return service.create(
            actorAdminId,
            AdminResourceType.GALLERY,
            CreateAdminResourceRequest("테스트 갤러리 생성", mapOf("workspaceId" to studio.id, "title" to slug)),
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

    private fun createConceptFolder(galleryId: Long, name: String): Long = jdbcClient.sql(
        """
        INSERT INTO concept_folders
            (gallery_id, name, sort_order, created_source, version, created_at, updated_at)
        VALUES (:galleryId, :name, 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id
        """.trimIndent(),
    )
        .param("galleryId", galleryId)
        .param("name", name)
        .query { rs, _ -> rs.getLong("id") }
        .single()

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
