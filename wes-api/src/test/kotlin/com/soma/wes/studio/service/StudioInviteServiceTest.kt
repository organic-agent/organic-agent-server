package com.soma.wes.studio.service

import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.service.GalleryInviteService
import com.soma.wes.notification.service.UserNotificationService
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class StudioInviteServiceTest @Autowired constructor(
    private val studioService: StudioService,
    private val inviteService: StudioInviteService,
    private val commonInviteService: GalleryInviteService,
    private val memberRepository: WorkspaceMemberRepository,
    private val notifications: UserNotificationService,
    private val users: UserFixture,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
    @Test
    fun `갤러리 없는 스튜디오에 합류하고 재수락은 소속과 알림을 중복 생성하지 않는다`() {
        val owner = users.사용자()
        val member = users.사용자()
        val studio = studioService.create(owner.requiredId, CreateStudioRequest("초대 스튜디오", "invite-studio"))
        val invite = inviteService.issue(studio.workspaceId, owner.requiredId)
        val token = invite.inviteUrl.substringAfterLast('/')
        val preview = commonInviteService.preview(token, member.requiredId)
        assertThat(preview.galleryId).isNull()
        assertThat(preview.kind).isEqualTo(GalleryInviteKind.STUDIO_MEMBER)
        assertThat(preview.status).isEqualTo(GalleryInviteStatus.ACTIVE)

        val accepted = commonInviteService.accept(token, member.requiredId)
        commonInviteService.accept(token, member.requiredId)
        assertThat(accepted.galleryId).isNull()
        assertThat(accepted.workspaceId).isEqualTo(studio.workspaceId)
        assertThat(studioService.get(studio.workspaceId, member.requiredId).role).isEqualTo(WorkspaceRole.MEMBER)
        assertThat(memberRepository.findAllByWorkspaceId(studio.workspaceId)).hasSize(2)
        assertThat(notifications.list(owner.requiredId, null, null)).hasSize(1)
        assertThat(commonInviteService.preview(token, member.requiredId).status).isEqualTo(GalleryInviteStatus.ALREADY_MEMBER)
        assertThat(inviteService.issue(studio.workspaceId, member.requiredId).inviteUrl).isNotBlank()
    }

    @Test
    fun `초대 재발급은 이전 링크를 폐기하고 외부인의 발급을 거절한다`() {
        val owner = users.사용자()
        val outsider = users.사용자()
        val studio = studioService.create(owner.requiredId, CreateStudioRequest("갱신 스튜디오", "renew-studio"))
        val old = inviteService.issue(studio.workspaceId, owner.requiredId).inviteUrl.substringAfterLast('/')
        inviteService.issue(studio.workspaceId, owner.requiredId)
        assertThat(commonInviteService.preview(old, outsider.requiredId).status).isEqualTo(GalleryInviteStatus.REVOKED)
        assertThatThrownBy { commonInviteService.accept(old, outsider.requiredId) }
            .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.INVITE_REVOKED)
        assertThatThrownBy { inviteService.issue(studio.workspaceId, outsider.requiredId) }
            .isInstanceOf(StudioException::class.java).extracting("errorCode").isEqualTo(StudioErrorCode.STUDIO_ACCESS_DENIED)
    }

    @Test
    fun `초대 작가의 소유자 전용 작업은 거절하고 소유자는 작가를 내보낼 수 있다`() {
        val owner = users.사용자()
        val member = users.사용자()
        val studio = studioService.create(owner.requiredId, CreateStudioRequest("권한 스튜디오", "roles-studio"))
        val token = inviteService.issue(studio.workspaceId, owner.requiredId).inviteUrl.substringAfterLast('/')
        commonInviteService.accept(token, member.requiredId)
        val membership = memberRepository.findByWorkspaceIdAndUserId(studio.workspaceId, member.requiredId)!!
        assertThatThrownBy { studioService.delete(studio.workspaceId, member.requiredId) }
            .isInstanceOf(StudioException::class.java).extracting("errorCode").isEqualTo(StudioErrorCode.NOT_STUDIO_OWNER)
        assertThatThrownBy { studioService.removeMember(studio.workspaceId, membership.requiredId, member.requiredId) }
            .isInstanceOf(StudioException::class.java).extracting("errorCode").isEqualTo(StudioErrorCode.NOT_STUDIO_OWNER)
        studioService.removeMember(studio.workspaceId, membership.requiredId, owner.requiredId)
        assertThat(memberRepository.findByWorkspaceIdAndUserId(studio.workspaceId, member.requiredId)).isNull()
        assertThat(notifications.list(member.requiredId, null, null)).hasSize(1)
    }
    @Test
    fun `만료된 작가 초대는 미리보기와 수락 모두 만료로 판정한다`() {
        val owner = users.사용자()
        val member = users.사용자()
        val studio = studioService.create(owner.requiredId, CreateStudioRequest("만료 스튜디오", "expired-studio"))
        val invite = inviteService.issue(studio.workspaceId, owner.requiredId)
        val token = invite.inviteUrl.substringAfterLast('/')
        jdbc.update("update studio_invites set expires_at = now() - interval '1 minute' where id = ?", invite.id)
        assertThat(commonInviteService.preview(token, member.requiredId).status).isEqualTo(GalleryInviteStatus.EXPIRED)
        assertThatThrownBy { commonInviteService.accept(token, member.requiredId) }
            .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.INVITE_EXPIRED)
        assertThat(memberRepository.findAllByWorkspaceId(studio.workspaceId)).hasSize(1)
    }
}
