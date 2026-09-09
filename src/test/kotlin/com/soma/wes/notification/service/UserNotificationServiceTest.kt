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
    private val galleryFixture: com.soma.wes.gallery.fixture.GalleryFixture,
    private val galleryRepository: com.soma.wes.gallery.repository.GalleryRepository,
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
    @Test
    fun `스튜디오에는 직접 알림과 그 갤러리 알림만 모이고 다른 사용자와 범위는 분리된다`() {
        // given
        val a = galleryFixture.멤버와_열린_갤러리()
        val b = galleryFixture.멤버와_열린_갤러리()
        val workspaceId = galleryRepository.findById(a.galleryId).orElseThrow().workspaceId
        val userId = a.photographer.requiredId
        for ((scope, id, title) in listOf(
            Triple(UserNotificationScope.STUDIO, workspaceId, "스튜디오"),
            Triple(UserNotificationScope.GALLERY, a.galleryId, "제출"),
            Triple(UserNotificationScope.GALLERY, b.galleryId, "다른 스튜디오"),
        )) notificationService.publish(listOf(userId), UserNotificationType.SELECTION_SUBMITTED, scope, id, title, title)
        notificationService.publish(listOf(b.photographer.requiredId), UserNotificationType.SELECTION_SUBMITTED,
            UserNotificationScope.GALLERY, a.galleryId, "다른 사용자", "다른 사용자")
        notificationService.publish(listOf(userId), UserNotificationType.WORKSPACE_DELETED,
            UserNotificationScope.GLOBAL, null, "전역", "전역")

        // when & then
        assertThat(notificationService.list(userId, UserNotificationScope.STUDIO, workspaceId).map { it.title })
            .containsExactly("제출", "스튜디오")
        assertThat(notificationService.list(userId, UserNotificationScope.GALLERY, a.galleryId).map { it.title })
            .containsExactly("제출")
        assertThat(notificationService.list(userId, UserNotificationScope.GLOBAL, null).map { it.title })
            .containsExactly("전역")
        val result = notificationService.read(userId, com.soma.wes.notification.dto.ReadUserNotificationsRequest(
            all = true, scope = UserNotificationScope.STUDIO, scopeId = workspaceId,
        ))
        assertThat(result.updatedCount).isEqualTo(2)
        assertThat(result.unreadCount).isEqualTo(2L)
        assertThat(notificationService.list(b.photographer.requiredId, null, null).single().readAt).isNull()
    }

    @Test
    fun `삭제된 갤러리의 알림도 발생 당시 스튜디오에서 조회하고 읽는다`() {
        // given
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val workspaceId = galleryRepository.findById(fixture.galleryId).orElseThrow().workspaceId
        jdbc.update("update galleries set deleted_at = now() where id = ?", fixture.galleryId)
        notificationService.publish(listOf(fixture.photographer.requiredId), UserNotificationType.GALLERY_REOPENED,
            UserNotificationScope.GALLERY, fixture.galleryId, "이력", "이력")
        jdbc.update("delete from galleries where id = ?", fixture.galleryId)

        // when
        val found = notificationService.list(fixture.photographer.requiredId, UserNotificationScope.STUDIO, workspaceId)
        val result = notificationService.read(fixture.photographer.requiredId,
            com.soma.wes.notification.dto.ReadUserNotificationsRequest(found.map { it.id }, scope = UserNotificationScope.STUDIO, scopeId = workspaceId))

        // then
        assertThat(found.single().title).isEqualTo("이력")
        assertThat(found.single().scopeId).isEqualTo(fixture.galleryId)
        assertThat(result.updatedCount).isEqualTo(1)
    }

    @Test
    fun `스튜디오 범위로 읽을 때 다른 스튜디오 알림이 섞이면 전체를 거절한다`() {
        // given
        val a = galleryFixture.멤버와_열린_갤러리()
        val b = galleryFixture.멤버와_열린_갤러리()
        val workspaceId = galleryRepository.findById(a.galleryId).orElseThrow().workspaceId
        for (id in listOf(a.galleryId, b.galleryId)) notificationService.publish(
            listOf(a.photographer.requiredId), UserNotificationType.SELECTION_SUBMITTED,
            UserNotificationScope.GALLERY, id, "제출", "제출")
        val ids = notificationService.list(a.photographer.requiredId, null, null).map { it.id }

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy {
            notificationService.read(a.photographer.requiredId, com.soma.wes.notification.dto.ReadUserNotificationsRequest(
                ids, scope = UserNotificationScope.STUDIO, scopeId = workspaceId,
            ))
        }.isInstanceOf(com.soma.wes.notification.exception.NotificationException::class.java)
            .extracting("errorCode").isEqualTo(com.soma.wes.notification.exception.NotificationErrorCode.NOTIFICATION_NOT_FOUND)
        assertThat(notificationService.list(a.photographer.requiredId, null, null)).allMatch { it.readAt == null }
    }

}
