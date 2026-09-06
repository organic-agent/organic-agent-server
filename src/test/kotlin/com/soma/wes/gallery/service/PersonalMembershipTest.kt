package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.IssueGalleryInviteRequest
import com.soma.wes.gallery.dto.response.GalleryParticipantRole
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class PersonalMembershipTest @Autowired constructor(
    private val users: UserFixture,
    private val workspaces: WorkspaceRepository,
    private val members: WorkspaceMemberRepository,
    private val galleries: GalleryRepository,
    private val invites: GalleryInviteService,
    private val galleryMembers: GalleryMemberService,
) {
    @Test
    fun `개인 파트너는 소유자 포함 두 명까지만 합류하고 역할 조회와 나가기를 지원한다`() {
        val owner = users.사용자()
        val partner = users.사용자()
        val outsider = users.사용자()
        val workspace = workspaces.findByPersonalOwnerUserId(owner.requiredId)!!
        val gallery = galleries.save(Gallery(workspace.requiredId, owner.requiredId, "개인 갤러리", status = GalleryStatus.OPEN))
        val request = IssueGalleryInviteRequest(kind = GalleryInviteKind.PERSONAL_PARTNER)
        val token = invites.issue(gallery.requiredId, owner.requiredId, request).inviteUrl.substringAfterLast('/')
        invites.acceptPartner(token, partner.requiredId)
        val renewed = invites.issue(gallery.requiredId, owner.requiredId, request).inviteUrl.substringAfterLast('/')
        assertThat(invites.preview(renewed, outsider.requiredId).status).isEqualTo(GalleryInviteStatus.FULL)
        assertThatThrownBy { invites.acceptPartner(renewed, outsider.requiredId) }
            .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.INVITE_FULL)
        assertThat(galleryMembers.list(gallery.requiredId, partner.requiredId).map { it.role })
            .containsExactly(GalleryParticipantRole.OWNER, GalleryParticipantRole.PARTNER)
        assertThatThrownBy { galleryMembers.leave(gallery.requiredId, owner.requiredId) }
            .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        galleryMembers.leave(gallery.requiredId, partner.requiredId)
        assertThat(members.findByWorkspaceIdAndUserId(workspace.requiredId, partner.requiredId)).isNull()
        invites.acceptPartner(renewed, outsider.requiredId)
        assertThat(members.findAllByWorkspaceId(workspace.requiredId)).hasSize(2)
    }
}
