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
