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

    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: ZonedDateTime,

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
            expiresAt: ZonedDateTime,
        ): AdminEntityRevision =
            AdminEntityRevision(
                targetType = targetType,
                targetId = targetId.take(128),
                revisionNumber = revisionNumber,
                operation = operation,
                beforeSnapshot = beforeSnapshot,
                afterSnapshot = afterSnapshot,
                expiresAt = expiresAt,
            )
    }
}
