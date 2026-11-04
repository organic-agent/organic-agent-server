package com.soma.wes.notification.repository

import com.soma.wes.notification.domain.UserNotificationSetting
import org.springframework.data.jpa.repository.JpaRepository

interface UserNotificationSettingRepository : JpaRepository<UserNotificationSetting, Long>
