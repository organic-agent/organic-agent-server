package com.soma.wes.notification.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(
    name = "user_notifications",
    indexes = [
        Index(name = "idx_user_notifications_user_created", columnList = "user_id, created_at, id"),
        Index(name = "idx_user_notifications_scope", columnList = "user_id, scope, scope_id, created_at"),
    ],
)
class UserNotification(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    val type: UserNotificationType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    val scope: UserNotificationScope,

    @Column(name = "scope_id", updatable = false)
    val scopeId: Long? = null,

    /** 알림 발생 시 스튜디오 소속. 갤러리 삭제 후에도 과거 알림을 모아 보여 준다. */
    @Column(name = "studio_workspace_id", updatable = false)
    val studioWorkspaceId: Long? = null,

    @Column(nullable = false, updatable = false, length = 100)
    val title: String,

    @Column(nullable = false, updatable = false, length = 500)
    val message: String,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "read_at")
    var readAt: ZonedDateTime? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 알림입니다." }
}

enum class UserNotificationType {
    WORKSPACE_DELETED,
    WORKSPACE_MEMBER_LEFT,
    GALLERY_MEMBER_LEFT,
    MEMBERSHIP_REMOVED,
    INVITE_ACCEPTED,
    GALLERY_OPENED,
    GALLERY_REOPENED,
    SELECTION_INCREASE_REQUESTED,
    SELECTION_INCREASE_APPROVED,
    PLAN_EXPIRED,
    DEADLINE_REMINDER,
    PLAN_EXPIRY_REMINDER,
    ANALYSIS_COMPLETED,

    RETOUCH_CONFIRMED,
    SELECTION_REOPENED,
    SELECTION_SUBMITTED,
    RETOUCH_REQUESTED,
    RETOUCH_COMPLETED,
}

enum class UserNotificationScope {
    GLOBAL,
    STUDIO,
    GALLERY,
}
