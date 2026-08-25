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

@Entity
@Table(name = "admin_audit_logs")
class AdminAuditLog private constructor(

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 60)
    val action: AdminAuditAction,

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 20)
    val outcome: AdminAuditOutcome,

    @Column(name = "actor_admin_id", updatable = false)
    val actorAdminId: Long?,

    @Column(name = "actor_username_snapshot", updatable = false, length = 64)
    val actorUsernameSnapshot: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", updatable = false, length = 60)
    val targetType: AdminAuditTargetType?,

    @Column(name = "target_id", updatable = false, length = 128)
    val targetId: String?,

    @Column(name = "target_label", updatable = false, length = 120)
    val targetLabel: String?,

    @Column(name = "source_address", updatable = false, length = 64)
    val sourceAddress: String?,

    @Column(name = "reason", updatable = false, length = REASON_MAX_LENGTH)
    val reason: String?,

    @Column(name = "changed_fields", updatable = false, length = 1000)
    private val changedFieldsValue: String?,

    @Column(name = "revision_number", updatable = false)
    val revisionNumber: Long?,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 관리자 감사 로그입니다." }

    val changedFields: List<String>
        get() = changedFieldsValue?.split(',')?.filter(String::isNotBlank).orEmpty()

    companion object {
        const val REASON_MAX_LENGTH = 500

        fun of(
            action: AdminAuditAction,
            outcome: AdminAuditOutcome,
            actorAdminId: Long?,
            actorUsernameSnapshot: String?,
            targetType: AdminAuditTargetType?,
            targetId: String?,
            targetLabel: String?,
            sourceAddress: String?,
            reason: String?,
            changedFields: Collection<String> = emptyList(),
            revisionNumber: Long? = null,
        ): AdminAuditLog =
            AdminAuditLog(
                action = action,
                outcome = outcome,
                actorAdminId = actorAdminId,
                actorUsernameSnapshot = actorUsernameSnapshot?.take(64),
                targetType = targetType,
                targetId = targetId?.take(128),
                targetLabel = targetLabel?.take(120),
                sourceAddress = sourceAddress?.take(64),
                reason = reason?.trim()?.takeIf(String::isNotBlank)?.take(REASON_MAX_LENGTH),
                changedFieldsValue = changedFields.distinct().sorted().joinToString(",").takeIf(String::isNotBlank),
                revisionNumber = revisionNumber,
            )
    }
}
