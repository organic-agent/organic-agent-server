package com.soma.wes.admin.audit.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(name = "admin_entity_revisions")
class AdminEntityRevision private constructor(

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 60)
    val targetType: AdminAuditTargetType,

    @Column(name = "target_id", nullable = false, updatable = false, length = 128)
    val targetId: String,

    @Column(name = "revision_number", nullable = false, updatable = false)
    val revisionNumber: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false, length = 60)
    val operation: AdminAuditAction,

    @Column(name = "before_snapshot", updatable = false, columnDefinition = "TEXT")
    val beforeSnapshot: String?,

    @Column(name = "after_snapshot", updatable = false, columnDefinition = "TEXT")
    val afterSnapshot: String?,

    @Column(name = "restore_expires_at", nullable = false, updatable = false)
    val restoreExpiresAt: ZonedDateTime,

    @Column(name = "snapshot_schema_version", nullable = false, updatable = false)
    val snapshotSchemaVersion: Int,

    @Column(name = "target_version", updatable = false)
    val targetVersion: Long?,

    /**
     * 영구 감사 조회용 snapshot과 분리된 7일 한정 복원 자료다. API DTO에는 노출하지 않고
     * restoreExpiresAt purge 및 cascade trash purge 시 payload만 제거하고 영구 snapshot 행은 유지한다.
     */
    @Column(name = "before_restore_payload", updatable = false, columnDefinition = "TEXT")
    val beforeRestorePayload: String?,

    @Column(name = "after_restore_payload", updatable = false, columnDefinition = "TEXT")
    val afterRestorePayload: String?,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 관리자 엔티티 리비전입니다." }

    companion object {
        fun of(
            targetType: AdminAuditTargetType,
            targetId: String,
            revisionNumber: Long,
            operation: AdminAuditAction,
            beforeSnapshot: String?,
            afterSnapshot: String?,
            restoreExpiresAt: ZonedDateTime,
            snapshotSchemaVersion: Int,
            targetVersion: Long?,
            beforeRestorePayload: String?,
            afterRestorePayload: String?,
        ): AdminEntityRevision =
            AdminEntityRevision(
                targetType = targetType,
                targetId = targetId.take(128),
                revisionNumber = revisionNumber,
                operation = operation,
                beforeSnapshot = beforeSnapshot,
                afterSnapshot = afterSnapshot,
                restoreExpiresAt = restoreExpiresAt,
                snapshotSchemaVersion = snapshotSchemaVersion,
                targetVersion = targetVersion,
                beforeRestorePayload = beforeRestorePayload,
                afterRestorePayload = afterRestorePayload,
            )
    }
}
