package com.soma.wes.admin.domain

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
                usernameSnapshot = usernameSnapshot,
                sourceAddress = sourceAddress?.take(64),
                reason = reason?.trim()?.takeIf { it.isNotBlank() },
                successful = successful,
            )
    }
}
