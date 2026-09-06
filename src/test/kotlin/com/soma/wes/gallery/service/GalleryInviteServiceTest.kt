package com.soma.wes.gallery.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.gallery.config.GalleryInviteProperties
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.response.GalleryInviteResponse
import com.soma.wes.gallery.dto.request.IssueGalleryInviteRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.ZonedDateTime

@DataJpaTest
@Import(
    TestcontainersConfiguration::class,
    TimeConfig::class,
    GalleryAccessPolicy::class,
    GalleryInviteService::class,
    com.soma.wes.studio.service.StudioInviteService::class,
    com.soma.wes.notification.service.UserNotificationService::class,
    SecureTokenGenerator::class,
    GalleryInviteUrlResolver::class,
)
@EnableConfigurationProperties(GalleryInviteProperties::class)
class GalleryInviteServiceTest @Autowired constructor(
    private val galleryInviteService: GalleryInviteService,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val galleryRepository: GalleryRepository,
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val clock: Clock,
) {

    private val now: ZonedDateTime get() = ZonedDateTime.now(clock)

    // 초대 수락이 사용자 종류를 확정하므로 실제 users 행이 있어야 한다.
    // 예전에는 임의의 id 상수로 충분했다.
    private var photographerId = 0L
    private var groomId = 0L
    private var brideId = 0L

    @BeforeEach
    fun setUpUsers() {
        photographerId = saveUser("photographer").let(::requiredId)
        groomId = saveUser("groom").let(::requiredId)
        brideId = saveUser("bride").let(::requiredId)
    }

    private fun saveUser(providerId: String): User = userRepository.save(
        User(
            provider = OAuthProvider.KAKAO,
            providerId = providerId,
            nickname = providerId,
            email = "$providerId@example.com",
        ),
    )

    private fun requiredId(user: User): Long = checkNotNull(user.id)

    private fun saveGallery(ownerUserId: Long = photographerId): Gallery {
        val workspace = workspaceRepository.save(Workspace.studio("스튜디오"))
        workspaceMemberRepository.save(
            WorkspaceMember(workspace.requiredId, ownerUserId, WorkspaceRole.OWNER),
        )
        val studio = studioRepository.save(
            Studio(userId = workspace.requiredId, name = "스튜디오", galleryUrl = "studio-${workspace.requiredId}"),
        )
        return galleryRepository.save(
            Gallery(
                workspaceId = studio.requiredId,
                createdByUserId = ownerUserId,
                title = "본식",
                status = GalleryStatus.OPEN,
            ),
        )
    }

    private fun galleryId(gallery: Gallery): Long = checkNotNull(gallery.id)

    private fun saveInvite(gallery: Gallery, expiresAt: ZonedDateTime): GalleryInvite =
        galleryInviteRepository.save(
            GalleryInvite(galleryId = galleryId(gallery), token = "fixed-token", expiresAt = expiresAt),
        )

    /**
     * 응답에는 토큰이 없다 — 조립이 끝난 링크만 준다. 수락을 호출하려면 저장된 행에서 꺼낸다.
     * 프론트도 같은 처지라 링크를 그대로 쓰지 토큰을 따로 다루지 않는다.
     */
    private fun tokenOf(invite: GalleryInviteResponse): String =
        galleryInviteRepository.findById(invite.id).orElseThrow().token

    @Nested
    @DisplayName("초대 링크를 발급할 때")
    inner class Issue {

        @Test
        fun `담당 작가는 초대 링크를 발급한다`() {
            // given
            val gallery = saveGallery()

            // when
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // then
            assertSoftly { softly ->
                softly.assertThat(invite.galleryId).isEqualTo(galleryId(gallery))
                softly.assertThat(invite.status).isEqualTo(GalleryInviteStatus.ACTIVE)
                softly.assertThat(invite.expiresAt.isAfter(now)).isTrue()
            }
        }

        @Test
        fun `발급 응답은 토큰이 아니라 완성된 링크를 준다`() {
            // 토큰만 주면 도메인을 붙이는 규칙이 링크 복사·카톡 공유·메일 본문에 각각 흩어진다.
            // given
            val gallery = saveGallery()

            // when
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // then
            assertThat(invite.inviteUrl).isEqualTo("http://localhost:3000/invite/${tokenOf(invite)}")
        }

        @Test
        fun `발급된 링크는 정해진 기간까지만 유효하다`() {
            // given
            val gallery = saveGallery()

            // when
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // then
            assertThat(invite.expiresAt.isAfter(now.plus(GalleryInviteService.VALIDITY).minusMinutes(1))).isTrue()
            assertThat(invite.expiresAt.isBefore(now.plus(GalleryInviteService.VALIDITY).plusMinutes(1))).isTrue()
        }

        @Test
        fun `미리보기는 초대 종류와 사용 가능 상태를 구분한다`() {
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(
                galleryId(gallery),
                photographerId,
                IssueGalleryInviteRequest(maxUses = 1),
            )
            val token = tokenOf(invite)

            val active = galleryInviteService.preview(token, groomId)
            galleryInviteService.accept(token, groomId)
            val alreadyMember = galleryInviteService.preview(token, groomId)
            val full = galleryInviteService.preview(token, brideId)

            assertSoftly { softly ->
                softly.assertThat(active.kind).isEqualTo(GalleryInviteKind.GALLERY_MEMBER)
                softly.assertThat(active.status).isEqualTo(GalleryInviteStatus.ACTIVE)
                softly.assertThat(active.maxUses).isEqualTo(1)
                softly.assertThat(alreadyMember.status).isEqualTo(GalleryInviteStatus.ALREADY_MEMBER)
                softly.assertThat(full.status).isEqualTo(GalleryInviteStatus.FULL)
            }
        }

        @Test
        fun `스튜디오 멤버 초대는 작업공간 소속을 만든다`() {
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(
                galleryId(gallery),
                photographerId,
                IssueGalleryInviteRequest(kind = GalleryInviteKind.STUDIO_MEMBER, maxUses = 1),
            )

            val result = galleryInviteService.accept(tokenOf(invite), groomId)

            assertThat(result.kind).isEqualTo(GalleryInviteKind.STUDIO_MEMBER)
            assertThat(result.memberId).isNull()
            assertThat(
                workspaceMemberRepository.findByWorkspaceIdAndUserId(gallery.workspaceId, groomId)?.role,
            ).isEqualTo(WorkspaceRole.MEMBER)
        }

        @Test
        fun `담당 작가가 아니면 링크를 발급할 수 없다`() {
            // given
            val gallery = saveGallery()

            // when & then
            assertThatThrownBy { galleryInviteService.issue(galleryId(gallery), userId = 999L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `재발급하면 이전 링크는 폐기된다`() {
            // 갤러리당 살아 있는 링크는 하나다. 여러 개가 살아 있으면 퍼진 링크를 거둬들이려 해도
            // 무엇을 폐기해야 하는지 알 수 없다.
            // given
            val gallery = saveGallery()
            val previous = galleryInviteService.issue(galleryId(gallery), photographerId)
            val previousToken = tokenOf(previous)

            // when
            val reissued = galleryInviteService.issue(galleryId(gallery), photographerId)

            // then
            assertThat(galleryInviteService.getCurrent(galleryId(gallery), photographerId).id)
                .isEqualTo(reissued.id)
            assertThatThrownBy { galleryInviteService.accept(previousToken, groomId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVITE_REVOKED)
        }
    }

    @Nested
    @DisplayName("초대를 수락할 때")
    inner class Accept {

        @Test
        fun `링크를 누르면 갤러리 멤버가 된다`() {
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // when
            val member = galleryInviteService.accept(tokenOf(invite), groomId)

            // then
            assertThat(member.galleryId).isEqualTo(galleryId(gallery))
            val saved = checkNotNull(galleryMemberRepository.findByGalleryIdAndUserId(galleryId(gallery), groomId))
            assertThat(member.memberId).isEqualTo(saved.id)
        }

        @Test
        fun `링크를 수락하면 예비 부부로 온보딩된다`() {
            // 예비 부부는 초대 링크로만 가입한다. 종류를 고르는 화면이 따로 없으므로
            // 수락이 곧 온보딩이다.
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // when
            galleryInviteService.accept(tokenOf(invite), groomId)

            // then
            assertThat(galleryMemberRepository.findByGalleryIdAndUserId(galleryId(gallery), groomId)).isNotNull()
        }

        @Test
        fun `작가가 남의 갤러리 초대를 수락해도 작가로 남는다`() {
            // 작업공간 역할과 무관하게 본인 결혼식 갤러리 초대를 수락할 수 있어야 한다.
            // given
            val otherGallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(otherGallery), photographerId)

            val guestPhotographerId = requiredId(saveUser("guest-photographer"))

            // when
            galleryInviteService.accept(tokenOf(invite), guestPhotographerId)

            // then
            assertThat(
                galleryMemberRepository.findByGalleryIdAndUserId(galleryId(otherGallery), guestPhotographerId),
            ).isNotNull()
        }

        @Test
        fun `같은 링크를 신랑과 신부가 각각 쓸 수 있다`() {
            // 1회용이면 작가가 연락처도 모르는 신부 몫까지 따로 발급해야 한다.
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // when
            val token = tokenOf(invite)
            galleryInviteService.accept(token, groomId)
            galleryInviteService.accept(token, brideId)

            // then
            assertThat(galleryMemberRepository.findAllByGalleryId(galleryId(gallery)).size).isEqualTo(2)
        }

        @Test
        fun `같은 사람이 링크를 여러 번 눌러도 멤버는 하나다`() {
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // when
            val token = tokenOf(invite)
            val first = galleryInviteService.accept(token, groomId)
            val second = galleryInviteService.accept(token, groomId)

            // then
            assertThat(second.memberId).isEqualTo(first.memberId)
            assertThat(galleryMemberRepository.findAllByGalleryId(galleryId(gallery)).size).isEqualTo(1)
        }

        @Test
        fun `만료된 링크는 쓸 수 없다`() {
            // given
            val gallery = saveGallery()
            val invite = saveInvite(gallery, expiresAt = now.minusMinutes(1))

            // when & then
            assertThatThrownBy { galleryInviteService.accept(invite.token, groomId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVITE_EXPIRED)
        }

        @Test
        fun `폐기된 링크는 만료 전이라도 쓸 수 없다`() {
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            val token = tokenOf(invite)
            galleryInviteService.revoke(galleryId(gallery), invite.id, photographerId)

            // when & then
            assertThatThrownBy { galleryInviteService.accept(token, groomId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVITE_REVOKED)
        }

        @Test
        fun `없는 토큰으로는 들어올 수 없다`() {
            // when & then
            assertThatThrownBy { galleryInviteService.accept("존재하지-않는-토큰", groomId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVITE_INVALID)
        }

        @Test
        fun `담당 작가는 자기 갤러리 초대를 수락할 수 없다`() {
            // 멤버가 되면 "작가는 고객 대신 사진을 고를 수 없다"는 규칙을 스스로 우회하게 된다.
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // when & then
            assertThatThrownBy { galleryInviteService.accept(tokenOf(invite), photographerId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.MANAGER_CANNOT_ACCEPT_INVITE)
        }
    }

    @Nested
    @DisplayName("링크를 폐기할 때")
    inner class Revoke {

        @Test
        fun `링크를 폐기해도 이미 들어온 멤버는 남는다`() {
            // 폐기는 "더 들어오지 못하게" 하는 것이지 내보내는 동작이 아니다.
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)
            galleryInviteService.accept(tokenOf(invite), groomId)

            // when
            galleryInviteService.revoke(galleryId(gallery), invite.id, photographerId)

            // then
            assertThat(galleryMemberRepository.findByGalleryIdAndUserId(galleryId(gallery), groomId)).isNotNull()
        }

        @Test
        fun `다른 갤러리의 초대를 자기 갤러리 권한으로 폐기할 수 없다`() {
            // given
            val mine = saveGallery(ownerUserId = photographerId)
            val otherOwnerId = requiredId(saveUser("other-owner"))
            val other = saveGallery(ownerUserId = otherOwnerId)
            val otherInvite = galleryInviteService.issue(galleryId(other), userId = otherOwnerId)

            // when & then
            assertThatThrownBy { galleryInviteService.revoke(galleryId(mine), otherInvite.id, photographerId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVITE_NOT_FOUND)
        }

        @Test
        fun `담당 작가가 아니면 링크를 폐기할 수 없다`() {
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

            // when & then
            assertThatThrownBy { galleryInviteService.revoke(galleryId(gallery), invite.id, userId = 999L) }
                .isInstanceOf(GalleryException::class.java)
        }
    }

    @Nested
    @DisplayName("현재 링크를 조회할 때")
    inner class GetCurrent {

        @Test
        fun `만료된 링크는 폐기 전까지 현재 링크로 남는다`() {
            // 걸러내면 작가가 "분명 발급했는데 없다"를 보게 되고, 다시 발급해야 하는 상황인지
            // 화면에서 알 방법이 사라진다.
            // given
            val gallery = saveGallery()
            val expired = saveInvite(gallery, expiresAt = now.minusMinutes(1))

            // when
            val current = galleryInviteService.getCurrent(galleryId(gallery), photographerId)

            // then
            assertThat(current.id).isEqualTo(checkNotNull(expired.id))
            assertThat(current.status).isEqualTo(GalleryInviteStatus.EXPIRED)
        }

        @Test
        fun `폐기만 해둔 갤러리는 현재 링크가 없다`() {
            // given
            val gallery = saveGallery()
            val invite = galleryInviteService.issue(galleryId(gallery), photographerId)
            galleryInviteService.revoke(galleryId(gallery), invite.id, photographerId)

            // when & then
            assertThatThrownBy { galleryInviteService.getCurrent(galleryId(gallery), photographerId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVITE_NOT_FOUND)
        }

        @Test
        fun `담당 작가가 아니면 현재 링크를 볼 수 없다`() {
            // given
            val gallery = saveGallery()

            // when & then
            assertThatThrownBy { galleryInviteService.getCurrent(galleryId(gallery), userId = 999L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("정원을 확인할 때")
    inner class MemberLimit {

        @Test
        fun `한 링크로 신랑과 신부 두 사람이 들어온다`() {
            // 부부는 공동 계정을 쓰지 않는다. 같은 링크를 각자 눌러 두 행이 생겨야 한다.
            // given
            val gallery = saveGallery()
            val token = tokenOf(galleryInviteService.issue(galleryId(gallery), photographerId))

            // when
            galleryInviteService.accept(token, groomId)
            galleryInviteService.accept(token, brideId)

            // then
            assertThat(galleryMemberRepository.countByGalleryId(galleryId(gallery))).isEqualTo(2L)
        }

        @Test
        fun `정원이 차면 세 번째 사람은 들어오지 못한다`() {
            // 수락에 작가의 승인 절차가 없으므로, 링크가 퍼졌을 때 이 상한이 유일한 방어선이다.
            // given
            val gallery = saveGallery()
            val token = tokenOf(galleryInviteService.issue(galleryId(gallery), photographerId))
            galleryInviteService.accept(token, groomId)
            galleryInviteService.accept(token, brideId)
            val stranger = requiredId(saveUser("stranger"))

            // when & then
            assertThatThrownBy { galleryInviteService.accept(token, stranger) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVITE_FULL)

            assertThat(galleryMemberRepository.countByGalleryId(galleryId(gallery))).isEqualTo(2L)
        }

        @Test
        fun `정원이 찼어도 이미 멤버인 사람의 재요청은 통과한다`() {
            // 멱등성이 정원보다 앞선다. 링크를 두 번 누른 신부에게 "정원이 찼다"를 보여줄 수는 없다.
            // given
            val gallery = saveGallery()
            val token = tokenOf(galleryInviteService.issue(galleryId(gallery), photographerId))
            val first = galleryInviteService.accept(token, groomId)
            galleryInviteService.accept(token, brideId)

            // when
            val again = galleryInviteService.accept(token, groomId)

            // then
            assertThat(again.memberId).isEqualTo(first.memberId)
            assertThat(galleryMemberRepository.countByGalleryId(galleryId(gallery))).isEqualTo(2L)
        }
    }
}
