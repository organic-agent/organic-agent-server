package com.soma.wes.notification.repository

import com.soma.wes.notification.domain.UserNotification
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationSetting
import com.soma.wes.notification.domain.UserNotificationType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.ZonedDateTime

interface UserNotificationRepository : JpaRepository<UserNotification, Long> {
    @Query("select n.userId from UserNotification n where n.type = :type and n.scope = :scope and n.scopeId = :scopeId and n.message = :message")
    fun findRecipientIds(type: UserNotificationType, scope: UserNotificationScope, scopeId: Long, message: String): List<Long>

    @Query("""
        select n from UserNotification n where n.userId = :userId and n.createdAt >= :since
          and (:scope is null or n.scope = :scope) and (:scopeId is null or n.scopeId = :scopeId)
        order by n.createdAt desc, n.id desc
    """)
    fun findRecent(userId: Long, since: ZonedDateTime, scope: UserNotificationScope?, scopeId: Long?): List<UserNotification>

    @Query("""
        select n from UserNotification n where n.userId = :userId and n.createdAt >= :since and (
            (n.scope = com.soma.wes.notification.domain.UserNotificationScope.STUDIO and (:workspaceId is null or n.scopeId = :workspaceId))
            or (n.scope = com.soma.wes.notification.domain.UserNotificationScope.GALLERY and n.studioWorkspaceId is not null
                and (:workspaceId is null or n.studioWorkspaceId = :workspaceId))
        ) order by n.createdAt desc, n.id desc
    """)
    fun findRecentForStudio(userId: Long, since: ZonedDateTime, workspaceId: Long?): List<UserNotification>

    /** 삭제된 부모의 콘텐츠를 반환하지 않고 알림 이력의 소속만 보존하는 조회다. */
    @Query(value = """
        select g.workspace_id from galleries g join workspaces w on w.id = g.workspace_id
        where g.id = :galleryId and w.type = 'STUDIO'
    """, nativeQuery = true)
    fun findStudioWorkspaceIdForHistory(galleryId: Long): Long?

    fun findAllByUserIdOrderByCreatedAtDescIdDesc(userId: Long): List<UserNotification>
    fun findAllByUserIdAndScopeOrderByCreatedAtDescIdDesc(
        userId: Long,
        scope: UserNotificationScope,
    ): List<UserNotification>

    fun findAllByUserIdAndScopeAndScopeIdOrderByCreatedAtDescIdDesc(
        userId: Long,
        scope: UserNotificationScope,
        scopeId: Long,
    ): List<UserNotification>
}

interface UserNotificationSettingRepository : JpaRepository<UserNotificationSetting, Long>
