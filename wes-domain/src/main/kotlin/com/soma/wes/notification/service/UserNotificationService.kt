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
import com.soma.wes.notification.dto.ReadUserNotificationsRequest
import com.soma.wes.notification.dto.ReadUserNotificationsResponse
import com.soma.wes.notification.exception.NotificationErrorCode
import com.soma.wes.notification.exception.NotificationException
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserNotificationService(
    private val notificationRepository: UserNotificationRepository,
    private val settingRepository: UserNotificationSettingRepository,
    private val clock: Clock,
) : UserNotificationPublisher {
    @Transactional(readOnly = true)
    fun list(userId: Long, scope: UserNotificationScope?, scopeId: Long?): List<UserNotificationResponse> {
        return recent(userId, scope, scopeId).map(UserNotificationResponse::from)
    }

    private fun recent(userId: Long, scope: UserNotificationScope?, scopeId: Long?): List<UserNotification> {
        val since = ZonedDateTime.now(clock).minusDays(RECENT_DAYS)
        return if (scope == UserNotificationScope.STUDIO) {
            notificationRepository.findRecentForStudio(userId, since, scopeId)
        } else {
            notificationRepository.findRecent(userId, since, scope, scopeId)
        }
    }

    @Transactional
    fun read(userId: Long, request: ReadUserNotificationsRequest): ReadUserNotificationsResponse {
        val ids = request.notificationIds.distinct()
        if ((request.all && ids.isNotEmpty()) || (!request.all && ids.isEmpty()) || ids.size > MAX_READ_BATCH ||
            (request.scopeId != null && (request.scope == null || request.scope == UserNotificationScope.GLOBAL))
        ) {
            throw NotificationException(NotificationErrorCode.INVALID_READ_REQUEST)
        }
        val recent = recent(userId, request.scope, request.scopeId)
        val targets = if (request.all) recent else recent.filter { it.requiredId in ids }
        if (!request.all && targets.size != ids.size) {
            throw NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND)
        }
        val unread = targets.filter { it.readAt == null }
        val now = ZonedDateTime.now(clock)
        unread.forEach { it.readAt = now }
        notificationRepository.flush()
        return ReadUserNotificationsResponse(
            updatedCount = unread.size,
            unreadCount = recent(userId, null, null).count { it.readAt == null }.toLong(),
        )
    }

    companion object {
        /** 알림 드롭다운에서 보여 주는 기간이다. */
        const val RECENT_DAYS = 30L
        /** 개별 읽음 요청의 최대 개수다. */
        const val MAX_READ_BATCH = 200
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
        val studioWorkspaceId = when (scope) {
            UserNotificationScope.STUDIO -> scopeId
            UserNotificationScope.GALLERY -> scopeId?.let(notificationRepository::findStudioWorkspaceIdForHistory)
            UserNotificationScope.GLOBAL -> null
        }
        val notifications = userIds.distinct().map { userId ->
            UserNotification(
                userId = userId,
                type = type,
                scope = scope,
                scopeId = scopeId,
                studioWorkspaceId = studioWorkspaceId,
                title = title,
                message = message,
            )
        }
        notificationRepository.saveAll(notifications)
    }
}
