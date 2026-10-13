package com.soma.wes.admin.domain

import com.soma.wes.admin.audit.domain.AdminAuditReasonCategory
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
@Table(name = "admin_auth_events")
class AdminAuthEvent private constructor(

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    val eventType: AdminEventType,

    @Column(name = "actor_admin_id", updatable = false)
    val actorAdminId: Long?,

    @Column(name = "target_admin_id", updatable = false)
    val targetAdminId: Long?,

    @Column(name = "username_snapshot", updatable = false, length = AdminAccount.USERNAME_MAX_LENGTH)
    val usernameSnapshot: String?,

    @Column(name = "source_address", updatable = false, length = 64)
    val sourceAddress: String?,

    @Column(name = "reason", updatable = false, length = REASON_MAX_LENGTH)
    val reason: String?,

    @Column(name = "successful", nullable = false, updatable = false)
    val successful: Boolean,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    companion object {
        const val REASON_MAX_LENGTH = 500

        @Suppress("UNUSED_PARAMETER")
        fun of(
            eventType: AdminEventType,
            actorAdminId: Long?,
            targetAdminId: Long?,
            usernameSnapshot: String?,
            sourceAddress: String?,
            reason: String?,
            successful: Boolean,
        ): AdminAuthEvent =
            AdminAuthEvent(
                eventType = eventType,
                actorAdminId = actorAdminId,
                targetAdminId = targetAdminId,
                // 로그인 이름은 계정 ID와 중복되는 장기 개인정보이므로 원문을 복제하지 않는다.
                usernameSnapshot = (targetAdminId ?: actorAdminId)?.let { "ADMIN #$it" },
                // 원격 IP는 불변 감사 DB에 영구 보존하지 않는다.
                sourceAddress = null,
                reason = "reasonCategory=${AdminAuditReasonCategory.fromOperatorText(reason).name} " +
                    "operatorReasonProvided=${!reason.isNullOrBlank()}",
                successful = successful,
            )
    }
}
