package com.soma.wes.notification.service

import com.soma.wes.notification.domain.UserNotification
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationSetting
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.dto.UpdateUserNotificationSettingsRequest
import com.soma.wes.notification.dto.UserNotificationResponse
import com.soma.wes.notification.dto.UserNotificationSettingsResponse
import com.soma.wes.notification.repository.UserNotificationRepository
import com.soma.wes.notification.repository.UserNotificationSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserNotificationService(
    private val notificationRepository: UserNotificationRepository,
    private val settingRepository: UserNotificationSettingRepository,
) : UserNotificationPublisher {
    @Transactional(readOnly = true)
    fun list(userId: Long, scope: UserNotificationScope?, scopeId: Long?): List<UserNotificationResponse> {
        val notifications = when {
            scope == null -> notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId)
            scopeId == null || scope == UserNotificationScope.GLOBAL ->
                notificationRepository.findAllByUserIdAndScopeOrderByCreatedAtDescIdDesc(userId, scope)
            else -> notificationRepository.findAllByUserIdAndScopeAndScopeIdOrderByCreatedAtDescIdDesc(userId, scope, scopeId)
        }
        return notifications.map(UserNotificationResponse::from)
    }

    @Transactional(readOnly = true)
    fun getSettings(userId: Long): UserNotificationSettingsResponse =
        settingRepository.findById(userId).map(UserNotificationSettingsResponse::from)
            .orElseGet(UserNotificationSettingsResponse::defaults)

    @Transactional
    fun updateSettings(
        userId: Long,
        request: UpdateUserNotificationSettingsRequest,
    ): UserNotificationSettingsResponse {
        val setting = settingRepository.findById(userId).orElseGet {
            UserNotificationSetting(userId = userId)
        }
        setting.update(request.emailEnabled, request.browserEnabled)
        return UserNotificationSettingsResponse.from(settingRepository.save(setting))
    }

    @Transactional
    override fun publish(
        userIds: Collection<Long>,
        type: UserNotificationType,
        scope: UserNotificationScope,
        scopeId: Long?,
        title: String,
        message: String,
    ) {
        require((scope == UserNotificationScope.GLOBAL) == (scopeId == null)) {
            "GLOBAL만 scopeId 없이 저장할 수 있습니다."
        }
        val notifications = userIds.distinct().map { userId ->
            UserNotification(
                userId = userId,
                type = type,
                scope = scope,
                scopeId = scopeId,
                title = title,
                message = message,
            )
        }
        notificationRepository.saveAll(notifications)
    }
}
