package com.soma.wes.notification.repository

import com.soma.wes.notification.domain.UserNotification
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationSetting
import org.springframework.data.jpa.repository.JpaRepository

interface UserNotificationRepository : JpaRepository<UserNotification, Long> {
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
