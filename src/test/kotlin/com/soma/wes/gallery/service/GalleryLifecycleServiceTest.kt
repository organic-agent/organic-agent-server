package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class GalleryLifecycleServiceTest @Autowired constructor(
    private val lifecycle: GalleryLifecycleService,
    private val galleries: GalleryRepository,
    private val workspaces: WorkspaceRepository,
    private val members: WorkspaceMemberRepository,
    private val users: UserFixture,
    private val notifications: UserNotificationService,
    private val clock: Clock,
) {
    @Test
    fun `만료된 개인 플랜은 보관 상태로 옮기고 모든 참여자에게 한 번만 알린다`() {
        val owner = users.사용자()
        val partner = users.사용자()
        val workspace = workspaces.findByPersonalOwnerUserId(owner.requiredId)!!
        for ((user, role) in listOf(partner to WorkspaceRole.MEMBER)) {
            members.save(WorkspaceMember(workspace.requiredId, user.requiredId, role))
        }
        val gallery = galleries.save(Gallery(workspace.requiredId, owner.requiredId, "기간 종료", status = GalleryStatus.OPEN).apply {
            planExpiresAt = ZonedDateTime.now(clock).minusMinutes(1)
        })
        assertThat(lifecycle.nextBatch(0)).contains(gallery.requiredId)
        lifecycle.process(gallery.requiredId)
        lifecycle.process(gallery.requiredId)
        val archived = galleries.findById(gallery.requiredId).orElseThrow()
        assertThat(archived.stage).isEqualTo(GalleryStage.ARCHIVED)
        assertThat(archived.status).isEqualTo(GalleryStatus.CLOSED)
        assertThat(archived.archivedUntil).isNull()
        assertThat(archived.retouchConfirmedAt).isNull()
        for (user in listOf(owner, partner)) {
            assertThat(notifications.list(user.requiredId, null, null).map { it.type })
                .containsExactly(UserNotificationType.PLAN_EXPIRED)
        }
        assertThat(lifecycle.nextBatch(0)).doesNotContain(gallery.requiredId)
    }

    @Test
    fun `마감과 플랜 만료 알림은 기한별로 한 번씩 보내고 기한 변경은 새 알림을 보낸다`() {
        val owner = users.사용자()
        val workspace = workspaces.findByPersonalOwnerUserId(owner.requiredId)!!
        val now = ZonedDateTime.now(clock)
        val gallery = galleries.save(Gallery(workspace.requiredId, owner.requiredId, "마감 임박", status = GalleryStatus.OPEN).apply {
            stage = GalleryStage.SELECTION_IN_PROGRESS
            selectionDeadline = now.plusDays(2)
            planExpiresAt = now.plusDays(2)
        })
        lifecycle.process(gallery.requiredId)
        lifecycle.process(gallery.requiredId)
        assertThat(notifications.list(owner.requiredId, null, null).map { it.type })
            .containsExactlyInAnyOrder(UserNotificationType.DEADLINE_REMINDER, UserNotificationType.PLAN_EXPIRY_REMINDER)
        val changed = galleries.findById(gallery.requiredId).orElseThrow()
        changed.selectionDeadline = now.plusDays(1)
        galleries.save(changed)
        lifecycle.process(gallery.requiredId)
        assertThat(notifications.list(owner.requiredId, null, null).filter { it.type == UserNotificationType.DEADLINE_REMINDER })
            .hasSize(2)
    }

    @Test
    fun `스튜디오는 개인 플랜 만료 규칙으로 보관하지 않고 삼일 밖의 마감은 알리지 않는다`() {
        val owner = users.사용자()
        val workspace = workspaces.save(Workspace.studio("스튜디오"))
        members.save(WorkspaceMember(workspace.requiredId, owner.requiredId, WorkspaceRole.OWNER))
        val gallery = galleries.save(Gallery(workspace.requiredId, owner.requiredId, "스튜디오 갤러리", status = GalleryStatus.OPEN).apply {
            stage = GalleryStage.SELECTION_IN_PROGRESS
            selectionDeadline = ZonedDateTime.now(clock).plusDays(4)
            planExpiresAt = ZonedDateTime.now(clock).minusDays(1)
        })
        lifecycle.process(gallery.requiredId)
        assertThat(galleries.findById(gallery.requiredId).orElseThrow().stage).isEqualTo(GalleryStage.SELECTION_IN_PROGRESS)
        assertThat(notifications.list(owner.requiredId, null, null)).isEmpty()
        assertThat(lifecycle.nextBatch(0)).doesNotContain(gallery.requiredId)
    }
}
