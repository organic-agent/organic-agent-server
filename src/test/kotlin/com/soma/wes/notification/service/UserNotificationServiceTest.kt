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
}
