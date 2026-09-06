package com.soma.wes.notification.service

import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.dto.UpdateUserNotificationSettingsRequest
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class UserNotificationServiceTest @Autowired constructor(
    private val notificationService: UserNotificationService,
    private val userFixture: UserFixture,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
    @Test
    fun `설정은 기본값을 주고 PUT 값으로 저장한다`() {
        val user = userFixture.사용자()

        assertThat(notificationService.getSettings(user.requiredId).emailEnabled).isTrue()

        val updated = notificationService.updateSettings(
            user.requiredId,
            UpdateUserNotificationSettingsRequest(emailEnabled = false, browserEnabled = true),
        )

        assertThat(updated.emailEnabled).isFalse()
        assertThat(notificationService.getSettings(user.requiredId)).isEqualTo(updated)
    }

    @Test
    fun `알림은 스튜디오와 갤러리 범위로 필터링한다`() {
        val user = userFixture.사용자()
        notificationService.publish(
            listOf(user.requiredId),
            UserNotificationType.WORKSPACE_MEMBER_LEFT,
            UserNotificationScope.STUDIO,
            10L,
            "스튜디오",
            "스튜디오 알림",
        )
        notificationService.publish(
            listOf(user.requiredId),
            UserNotificationType.SELECTION_SUBMITTED,
            UserNotificationScope.GALLERY,
            20L,
            "갤러리",
            "갤러리 알림",
        )

        assertThat(notificationService.list(user.requiredId, UserNotificationScope.STUDIO, null))
            .extracting<String> { it.title }
            .containsExactly("스튜디오")
        assertThat(notificationService.list(user.requiredId, UserNotificationScope.GALLERY, 20L))
            .extracting<String> { it.title }
            .containsExactly("갤러리")
    }

    @Test
    fun `읽음은 내 알림만 원자적으로 처리하고 재시도는 중복 반영하지 않는다`() {
        val user = userFixture.사용자()
        val other = userFixture.사용자()
        for (id in listOf(user.requiredId, other.requiredId)) notificationService.publish(
            listOf(id), UserNotificationType.INVITE_ACCEPTED, UserNotificationScope.STUDIO, 10L, "초대", "합류",
        )
        val mine = notificationService.list(user.requiredId, null, null).single()
        val theirs = notificationService.list(other.requiredId, null, null).single()
        org.assertj.core.api.Assertions.assertThatThrownBy {
            notificationService.read(user.requiredId, com.soma.wes.notification.dto.ReadUserNotificationsRequest(listOf(mine.id, theirs.id)))
        }.isInstanceOf(com.soma.wes.notification.exception.NotificationException::class.java)
            .extracting("errorCode").isEqualTo(com.soma.wes.notification.exception.NotificationErrorCode.NOTIFICATION_NOT_FOUND)
        assertThat(notificationService.list(user.requiredId, null, null).single().readAt).isNull()
        val request = com.soma.wes.notification.dto.ReadUserNotificationsRequest(listOf(mine.id))
        assertThat(notificationService.read(user.requiredId, request).updatedCount).isEqualTo(1)
        assertThat(notificationService.read(user.requiredId, request).updatedCount).isZero()
        assertThat(notificationService.list(other.requiredId, null, null).single().readAt).isNull()
    }

    @Test
    fun `30일이 지난 알림은 제외하고 모두 읽음은 지정한 범위만 반영한다`() {
        val user = userFixture.사용자()
        for (scopeId in listOf(10L, 20L, 30L)) notificationService.publish(
            listOf(user.requiredId), UserNotificationType.INVITE_ACCEPTED, UserNotificationScope.STUDIO, scopeId, "초대", "합류",
        )
        jdbc.update("update user_notifications set created_at = now() - interval '31 days' where scope_id = 30")
        assertThat(notificationService.list(user.requiredId, null, null)).hasSize(2)
        val result = notificationService.read(user.requiredId, com.soma.wes.notification.dto.ReadUserNotificationsRequest(
            all = true, scope = UserNotificationScope.STUDIO, scopeId = 10L,
        ))
        assertThat(result.updatedCount).isEqualTo(1)
        assertThat(result.unreadCount).isEqualTo(1L)
    }
}
