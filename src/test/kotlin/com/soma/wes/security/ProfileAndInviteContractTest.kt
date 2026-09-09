package com.soma.wes.security

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfoDto
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.auth.service.oauth.OAuthLoginProcessor
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.IssueGalleryInviteRequest
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.service.GalleryInviteService
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.studio.service.StudioInviteService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.TestSequence
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class ProfileAndInviteContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokens: AuthTokenProvider,
    private val login: OAuthLoginProcessor,
    private val users: UserFixture,
    private val userRepository: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val galleries: GalleryRepository,
    private val invites: GalleryInviteService,
    private val inviteRepository: GalleryInviteRepository,
    private val studios: StudioFixture,
    private val studioInvites: StudioInviteService,
) {
    @Test
    fun `가입과 재로그인 사진이 저장되고 내 정보 API에 반영된다`() {
        val providerId = "profile-${TestSequence.next()}"
        val first = OAuthUserInfoDto(OAuthProvider.GOOGLE, providerId, "내 별명", "user@example.com",
            "https://images.example.com/first.jpg")
        login.process(first)
        val user = userRepository.findByProviderAndProviderId(OAuthProvider.GOOGLE, providerId)!!
        val token = tokens.generateAccessToken(user).value
        mvc.get("/api/v1/users/me") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.profileImageUrl") { value(first.profileImageUrl) }
        }
        login.process(first.copy(nickname = "소셜에서 바꾼 이름", profileImageUrl = "https://images.example.com/next.jpg"))
        mvc.get("/api/v1/users/me") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.nickname") { value("내 별명") }
            jsonPath("$.profileImageUrl") { value("https://images.example.com/next.jpg") }
        }
        login.process(first.copy(profileImageUrl = null))
        assertThat(userRepository.findById(user.requiredId).orElseThrow().profileImageUrl).isNull()
        mvc.get("/api/v1/users/me") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.profileImageUrl") { doesNotExist() }
        }
    }

    @Test
    fun `초대 수락 전 발급자 이름만 읽고 수락 전까지 갤러리 접근은 닫혀 있다`() {
        val owner = users.사용자("초대한 수민")
        val guest = users.사용자("초대받은 사람")
        val workspace = workspaces.findByPersonalOwnerUserId(owner.requiredId)!!
        val gallery = galleries.save(Gallery(workspace.requiredId, owner.requiredId, "우리 사진", status = GalleryStatus.OPEN))
        val response = invites.issue(gallery.requiredId, owner.requiredId,
            IssueGalleryInviteRequest(kind = GalleryInviteKind.PERSONAL_PARTNER))
        val invite = inviteRepository.findById(response.id).orElseThrow()
        val access = tokens.generateAccessToken(guest).value
        mvc.get("/api/v1/invites/${invite.token}") { header("Authorization", "Bearer $access") }.andExpect {
            status { isOk() }
            jsonPath("$.inviterNickname") { value("초대한 수민") }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.email") { doesNotExist() }
            jsonPath("$.issuedByUserId") { doesNotExist() }
        }
        assertThat(invite.issuedByUserId).isEqualTo(owner.requiredId)
        mvc.get("/api/v1/galleries/${gallery.requiredId}") { header("Authorization", "Bearer $access") }
            .andExpect { status { isForbidden() } }
        mvc.post("/api/v1/invites/${invite.token}/accept") { header("Authorization", "Bearer $access") }
            .andExpect { status { isOk() } }
        mvc.get("/api/v1/galleries/${gallery.requiredId}") { header("Authorization", "Bearer $access") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `기존 개인 초대는 소유자 이름으로 표시한다`() {
        val owner = users.사용자("기존 개인 소유자")
        val guest = users.사용자()
        val workspace = workspaces.findByPersonalOwnerUserId(owner.requiredId)!!
        val gallery = galleries.save(Gallery(workspace.requiredId, owner.requiredId, "우리 사진", status = GalleryStatus.OPEN))
        val legacy = inviteRepository.save(GalleryInvite(gallery.requiredId, "legacy-${TestSequence.next()}",
            kind = GalleryInviteKind.PERSONAL_PARTNER, expiresAt = ZonedDateTime.now().plusDays(7)))
        assertThat(invites.preview(legacy.token, guest.requiredId).inviterNickname).isEqualTo("기존 개인 소유자")
    }

    @Test
    fun `스튜디오 초대와 갤러리 초대는 생성자가 아닌 실제 발급자 이름을 반환한다`() {
        val owner = users.사용자("스튜디오 대표")
        val issuer = users.사용자("링크를 발급한 작가")
        val guest = users.사용자()
        val studio = studios.스튜디오(owner)
        workspaceMembers.save(WorkspaceMember(studio.userId, issuer.requiredId, WorkspaceRole.MEMBER))
        val gallery = galleries.save(Gallery(studio.userId, owner.requiredId, "웨딩 사진", status = GalleryStatus.OPEN))
        val galleryInvite = invites.issue(gallery.requiredId, issuer.requiredId)
        val galleryToken = inviteRepository.findById(galleryInvite.id).orElseThrow().token
        val studioToken = studioInvites.issue(studio.userId, issuer.requiredId).inviteUrl.substringAfterLast('/')
        assertThat(invites.preview(galleryToken, guest.requiredId).inviterNickname).isEqualTo(issuer.nickname)
        assertThat(invites.preview(studioToken, guest.requiredId).inviterNickname).isEqualTo(issuer.nickname)
        // 발급자가 탈퇴해도 기존 초대의 조회가 깨지거나 대표 이름으로 둔갑하지 않는다.
        userRepository.deleteById(issuer.requiredId)
        assertThat(invites.preview(galleryToken, guest.requiredId).inviterNickname).isNull()
        assertThat(invites.preview(studioToken, guest.requiredId).inviterNickname).isNull()
    }
}
