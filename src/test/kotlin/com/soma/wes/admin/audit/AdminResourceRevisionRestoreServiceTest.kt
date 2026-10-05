package com.soma.wes.admin.audit

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.dto.request.AdminRevisionRestoreRequest
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.audit.service.AdminAuditQueryService
import com.soma.wes.admin.audit.service.AdminRevisionRestoreService
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class AdminResourceRevisionRestoreServiceTest @Autowired constructor(
    private val resourceService: AdminResourceService,
    private val restoreService: AdminRevisionRestoreService,
    private val revisionRepository: AdminEntityRevisionRepository,
    private val queryService: AdminAuditQueryService,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `일반 수정 가능한 주요 5개 리소스는 허용 필드를 이전 리비전으로 복원하고 복원 감사와 새 버전을 남긴다`() {
        val actor = adminAccountFixture.관리자("six-resource-restore")
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE",
            "providerId" to "restore-eight-user",
            "nickname" to "원본 사용자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to user.id,
            "name" to "원본 스튜디오",
            "galleryUrl" to "restore-eight",
            "contact" to "02-111-2222",
            "description" to "원본 소개",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id,
            "title" to "원본 갤러리",
        ))
        val photo = create(actor.requiredId, AdminResourceType.PHOTO, mapOf(
            "galleryId" to gallery.id,
            "storageKey" to "restore-eight/original.jpg",
            "originalFileName" to "original.jpg",
            "contentType" to "image/jpeg",
            "displayOrder" to 10,
        ))
        val collaboration = create(actor.requiredId, AdminResourceType.COLLABORATION, mapOf(
            "galleryId" to gallery.id,
            "name" to "원본 협업",
        ))
        val cases = listOf(
            RestoreCase(AdminResourceType.USER, user.id, "nickname", "원본 사용자", "변경 사용자"),
            RestoreCase(AdminResourceType.STUDIO, studio.id, "contact", "02-111-2222", "010-9999-0000"),
            RestoreCase(AdminResourceType.GALLERY, gallery.id, "title", "원본 갤러리", "변경 갤러리"),
            RestoreCase(AdminResourceType.PHOTO, photo.id, "displayOrder", 10, 20),
            RestoreCase(AdminResourceType.COLLABORATION, collaboration.id, "name", "원본 협업", "변경 협업"),
        )
        val selectedRevisions = cases.associateWith { target ->
            revisionRepository.findAllByTargetTypeAndTargetIdOrderByRevisionNumberDesc(
                target.type.auditTargetType,
                target.id.toString(),
            ).minBy { it.revisionNumber }
        }

        cases.forEach { target ->
            when (target.type) {
                AdminResourceType.STUDIO -> jdbcTemplate.update(
                    "UPDATE studios SET inflow_channel = 'LEGACY_BLOG' WHERE workspace_id = ?",
                    target.id,
                )
                AdminResourceType.GALLERY -> jdbcTemplate.update(
                    "UPDATE galleries SET stage = 'RETOUCH' WHERE id = ?",
                    target.id,
                )
                else -> Unit
            }
            val beforeUpdate = resourceService.get(target.type, target.id)
            resourceService.update(
                actor.requiredId,
                target.type,
                target.id,
                UpdateAdminResourceRequest("복원 전 변경", beforeUpdate.version, mapOf(target.field to target.changed)),
                "127.0.0.1",
            )
            val current = resourceService.get(target.type, target.id)

            val auditId = restoreService.restore(
                actorAdminId = actor.requiredId,
                revisionId = selectedRevisions.getValue(target).requiredId,
                request = AdminRevisionRestoreRequest("선택 리비전 복원", current.version),
                sourceAddress = "127.0.0.1",
            )

            val restored = resourceService.get(target.type, target.id)
            assertThat(restored.fields[target.field]).isEqualTo(target.original)
            if (target.type == AdminResourceType.STUDIO) {
                assertThat(restored.fields["inflowChannel"]).isEqualTo("LEGACY_BLOG")
            }
            if (target.type == AdminResourceType.GALLERY) {
                assertThat(restored.fields["stage"]).isEqualTo("RETOUCH")
            }
            assertThat(restored.version).isEqualTo(current.version + 1)
            assertThat(queryService.getDetail(auditId).audit.action).isEqualTo(AdminAuditAction.REVISION_RESTORED)
            assertThat(
                revisionRepository.findAllByTargetTypeAndTargetIdOrderByRevisionNumberDesc(
                    target.type.auditTargetType,
                    target.id.toString(),
                ),
            ).hasSize(3)
        }
    }

    @Test
    fun `리비전 복원은 현재 버전과 스냅샷 스키마 및 불변 소유 관계를 검증한다`() {
        val actor = adminAccountFixture.관리자("revision-boundary")
        val firstOwner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "revision-owner-1", "nickname" to "첫 소유자",
        ))
        val secondOwner = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "NAVER", "providerId" to "revision-owner-2", "nickname" to "둘째 소유자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to firstOwner.id, "name" to "원본 스튜디오", "galleryUrl" to "revision-boundary",
        ))
        val revision = revisionRepository.findAllByTargetTypeAndTargetIdOrderByRevisionNumberDesc(
            AdminResourceType.STUDIO.auditTargetType,
            studio.id.toString(),
        ).single()
        val changed = resourceService.update(
            actor.requiredId,
            AdminResourceType.STUDIO,
            studio.id,
            UpdateAdminResourceRequest("이름 변경", studio.version, mapOf("name" to "변경 스튜디오")),
            "127.0.0.1",
        )

        assertRestoreError(revision.requiredId, changed.version - 1, AdminErrorCode.RESOURCE_VERSION_CONFLICT)

        val unsupportedSchemaRevisionId = jdbcTemplate.queryForObject(
            """
            INSERT INTO admin_entity_revisions (
                target_type, target_id, revision_number, operation, before_snapshot, after_snapshot,
                restore_expires_at, snapshot_schema_version, target_version,
                before_restore_payload, after_restore_payload, created_at, updated_at
            )
            SELECT target_type, target_id, revision_number + 1000, operation, before_snapshot, after_snapshot,
                   CURRENT_TIMESTAMP + INTERVAL '1 day', 999, target_version,
                   before_restore_payload, after_restore_payload, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM admin_entity_revisions WHERE id = ?
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            revision.requiredId,
        )!!
        assertRestoreError(unsupportedSchemaRevisionId, changed.version, AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        assertThat(queryService.getDetailForRevision(unsupportedSchemaRevisionId)).isFalse()

        val missingPayloadRevisionId = jdbcTemplate.queryForObject(
            """
            INSERT INTO admin_entity_revisions (
                target_type, target_id, revision_number, operation, before_snapshot, after_snapshot,
                restore_expires_at, snapshot_schema_version, target_version,
                before_restore_payload, after_restore_payload, created_at, updated_at
            )
            SELECT target_type, target_id, revision_number + 1001, operation, before_snapshot, after_snapshot,
                   CURRENT_TIMESTAMP + INTERVAL '1 day', ${AdminAuditService.CURRENT_SNAPSHOT_SCHEMA_VERSION}, target_version,
                   before_restore_payload, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM admin_entity_revisions WHERE id = ?
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            revision.requiredId,
        )!!
        assertRestoreError(missingPayloadRevisionId, changed.version, AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)

        val missingTargetVersionRevisionId = jdbcTemplate.queryForObject(
            """
            INSERT INTO admin_entity_revisions (
                target_type, target_id, revision_number, operation, before_snapshot, after_snapshot,
                restore_expires_at, snapshot_schema_version, target_version,
                before_restore_payload, after_restore_payload, created_at, updated_at
            )
            SELECT target_type, target_id, revision_number + 1003, operation, before_snapshot, after_snapshot,
                   CURRENT_TIMESTAMP + INTERVAL '1 day', snapshot_schema_version, NULL,
                   before_restore_payload, after_restore_payload, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM admin_entity_revisions WHERE id = ?
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            revision.requiredId,
        )!!
        assertRestoreError(missingTargetVersionRevisionId, changed.version, AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        assertThat(queryService.getDetailForRevision(missingTargetVersionRevisionId)).isFalse()

        val tamperedRelationRevisionId = jdbcTemplate.queryForObject(
            """
            INSERT INTO admin_entity_revisions (
                target_type, target_id, revision_number, operation, before_snapshot, after_snapshot,
                restore_expires_at, snapshot_schema_version, target_version,
                before_restore_payload, after_restore_payload, created_at, updated_at
            )
            SELECT target_type, target_id, revision_number + 1002, operation, before_snapshot, after_snapshot,
                   CURRENT_TIMESTAMP + INTERVAL '1 day', snapshot_schema_version, target_version,
                   before_restore_payload,
                   jsonb_set(
                    CAST(after_restore_payload AS JSONB),
                    '{ownerUserId}',
                    to_jsonb(CAST(? AS BIGINT))
                   )::TEXT,
                   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM admin_entity_revisions WHERE id = ?
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            secondOwner.id,
            revision.requiredId,
        )!!
        assertRestoreError(tamperedRelationRevisionId, changed.version, AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)

        val unchanged = resourceService.get(AdminResourceType.STUDIO, studio.id)
        assertThat(unchanged.fields["ownerUserId"]).isEqualTo(firstOwner.id)
        assertThat(unchanged.fields["name"]).isEqualTo("변경 스튜디오")
        assertThat(unchanged.version).isEqualTo(changed.version)
    }

    @Test
    fun `자동 생성 셀렉은 생성 리비전을 만들지 않고 보정 요청 리비전은 복원 가능으로 광고하지 않는다`() {
        val actor = adminAccountFixture.관리자("unsupported-revision-restore")
        val user = create(actor.requiredId, AdminResourceType.USER, mapOf(
            "provider" to "GOOGLE", "providerId" to "unsupported-restore-user", "nickname" to "사용자",
        ))
        val studio = create(actor.requiredId, AdminResourceType.STUDIO, mapOf(
            "ownerUserId" to user.id, "name" to "스튜디오", "galleryUrl" to "unsupported-restore",
        ))
        val gallery = create(actor.requiredId, AdminResourceType.GALLERY, mapOf(
            "workspaceId" to studio.id, "title" to "갤러리",
        ))
        val selection = create(actor.requiredId, AdminResourceType.SELECTION, mapOf("galleryId" to gallery.id))
        val retouch = create(actor.requiredId, AdminResourceType.RETOUCH_REQUEST, mapOf(
            "galleryId" to gallery.id, "roundNo" to 1,
        ))

        assertThat(queryService.getRevisions(AdminResourceType.SELECTION.auditTargetType, selection.id.toString()))
            .isEmpty()
        assertThat(
            queryService.getRevisions(AdminResourceType.RETOUCH_REQUEST.auditTargetType, retouch.id.toString())
                .single().restorable,
        ).isFalse()
    }

    private fun assertRestoreError(revisionId: Long, expectedVersion: Long, errorCode: AdminErrorCode) {
        assertThatThrownBy {
            restoreService.restore(
                actorAdminId = 1,
                revisionId = revisionId,
                request = AdminRevisionRestoreRequest("경계 검증", expectedVersion),
                sourceAddress = "127.0.0.1",
            )
        }.isInstanceOf(AdminException::class.java)
            .hasMessage(errorCode.message)
    }

    /** malformed copy가 API에서 복원 가능으로 광고되지 않는지 id로 직접 확인한다. */
    private fun AdminAuditQueryService.getDetailForRevision(revisionId: Long): Boolean {
        val auditId = jdbcTemplate.queryForObject(
            """
            INSERT INTO admin_audit_logs (
                action, outcome, target_type, target_id, reason, revision_number, created_at, updated_at
            )
            SELECT operation, 'SUCCESS', target_type, target_id,
                   'reasonCategory=UNSPECIFIED operatorReasonProvided=false', revision_number,
                   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM admin_entity_revisions WHERE id = ?
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            revisionId,
        )!!
        return requireNotNull(getDetail(auditId).revision).restorable
    }

    private fun create(actorId: Long, type: AdminResourceType, fields: Map<String, Any?>) =
        if (type == AdminResourceType.SELECTION) {
            val galleryId = (fields.getValue("galleryId") as Number).toLong()
            val selectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM photo_selections WHERE gallery_id = ?",
                Long::class.java,
                galleryId,
            )!!
            resourceService.get(type, selectionId)
        } else {
            resourceService.create(actorId, type, CreateAdminResourceRequest("리비전 테스트 생성", fields), "127.0.0.1")
        }

    private data class RestoreCase(
        val type: AdminResourceType,
        val id: Long,
        val field: String,
        val original: Any,
        val changed: Any,
    )
}
