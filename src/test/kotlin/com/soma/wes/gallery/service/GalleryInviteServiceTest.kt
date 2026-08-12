package com.soma.wes.gallery.service

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryInviteStatus
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.config.GalleryInviteProperties
import com.soma.wes.gallery.dto.response.GalleryInviteResponse
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.repository.UserRepository
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@Import(
    TestcontainersConfiguration::class,
    TimeConfig::class,
    GalleryAccessPolicy::class,
    GalleryInviteService::class,
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
        val studio = studioRepository.save(
            Studio(userId = ownerUserId, name = "스튜디오", galleryUrl = "studio-$ownerUserId"),
        )
        return galleryRepository.save(
            Gallery(studioId = checkNotNull(studio.id), title = "본식", status = GalleryStatus.OPEN),
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

    @Test
    fun `담당 작가는 초대 링크를 발급한다`() {
        val gallery = saveGallery()

        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        assertEquals(galleryId(gallery), invite.galleryId)
        assertEquals(GalleryInviteStatus.ACTIVE, invite.status)
        assertTrue(invite.expiresAt.isAfter(now))
    }

    @Test
    fun `발급 응답은 토큰이 아니라 완성된 링크를 준다`() {
        // 토큰만 주면 도메인을 붙이는 규칙이 링크 복사·카톡 공유·메일 본문에 각각 흩어진다.
        val gallery = saveGallery()

        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        assertEquals("http://localhost:3000/invite/${tokenOf(invite)}", invite.inviteUrl)
    }

    @Test
    fun `발급된 링크는 정해진 기간까지만 유효하다`() {
        val gallery = saveGallery()

        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        assertTrue(invite.expiresAt.isAfter(now.plus(GalleryInviteService.VALIDITY).minusMinutes(1)))
        assertTrue(invite.expiresAt.isBefore(now.plus(GalleryInviteService.VALIDITY).plusMinutes(1)))
    }

    @Test
    fun `담당 작가가 아니면 링크를 발급할 수 없다`() {
        val gallery = saveGallery()

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.issue(galleryId(gallery), userId = 999L)
        }

        assertEquals(GalleryErrorCode.GALLERY_ACCESS_DENIED, exception.errorCode)
    }

    @Test
    fun `링크를 누르면 갤러리 멤버가 된다`() {
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        val member = galleryInviteService.accept(tokenOf(invite), groomId)

        assertEquals(galleryId(gallery), member.galleryId)
        val saved = galleryMemberRepository.findByGalleryIdAndUserId(galleryId(gallery), groomId)
        assertNotNull(saved)
        assertEquals(saved.id, member.memberId)
    }

    @Test
    fun `링크를 수락하면 예비 부부로 온보딩된다`() {
        // 예비 부부는 초대 링크로만 가입한다. 종류를 고르는 화면이 따로 없으므로
        // 수락이 곧 온보딩이다.
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        galleryInviteService.accept(tokenOf(invite), groomId)

        assertEquals(UserType.CLIENT, userRepository.findById(groomId).orElseThrow().userType)
    }

    @Test
    fun `작가가 남의 갤러리 초대를 수락해도 작가로 남는다`() {
        // 무조건 CLIENT로 덮어쓰면 이미 PHOTOGRAPHER인 사용자가 USER_TYPE_ALREADY_SELECTED에
        // 걸려 수락 자체가 실패한다. 본인 결혼식 갤러리에 초대받는 것은 정상 시나리오다.
        val otherGallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(otherGallery), photographerId)

        val guestPhotographerId = requiredId(saveUser("guest-photographer"))
        userRepository.findById(guestPhotographerId).orElseThrow().selectType(UserType.PHOTOGRAPHER)

        galleryInviteService.accept(tokenOf(invite), guestPhotographerId)

        assertEquals(
            UserType.PHOTOGRAPHER,
            userRepository.findById(guestPhotographerId).orElseThrow().userType,
        )
    }

    @Test
    fun `같은 링크를 신랑과 신부가 각각 쓸 수 있다`() {
        // 1회용이면 작가가 연락처도 모르는 신부 몫까지 따로 발급해야 한다.
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        val token = tokenOf(invite)
        galleryInviteService.accept(token, groomId)
        galleryInviteService.accept(token, brideId)

        assertEquals(2, galleryMemberRepository.findAllByGalleryId(galleryId(gallery)).size)
    }

    @Test
    fun `같은 사람이 링크를 여러 번 눌러도 멤버는 하나다`() {
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        val token = tokenOf(invite)
        val first = galleryInviteService.accept(token, groomId)
        val second = galleryInviteService.accept(token, groomId)

        assertEquals(first.memberId, second.memberId)
        assertEquals(1, galleryMemberRepository.findAllByGalleryId(galleryId(gallery)).size)
    }

    @Test
    fun `만료된 링크는 쓸 수 없다`() {
        val gallery = saveGallery()
        val invite = saveInvite(gallery, expiresAt = now.minusMinutes(1))

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.accept(invite.token, groomId)
        }

        assertEquals(GalleryErrorCode.INVITE_EXPIRED, exception.errorCode)
    }

    @Test
    fun `폐기된 링크는 만료 전이라도 쓸 수 없다`() {
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        val token = tokenOf(invite)
        galleryInviteService.revoke(galleryId(gallery), invite.id, photographerId)

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.accept(token, groomId)
        }
        assertEquals(GalleryErrorCode.INVITE_REVOKED, exception.errorCode)
    }

    @Test
    fun `링크를 폐기해도 이미 들어온 멤버는 남는다`() {
        // 폐기는 "더 들어오지 못하게" 하는 것이지 내보내는 동작이 아니다.
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)
        galleryInviteService.accept(tokenOf(invite), groomId)

        galleryInviteService.revoke(galleryId(gallery), invite.id, photographerId)

        assertNotNull(galleryMemberRepository.findByGalleryIdAndUserId(galleryId(gallery), groomId))
    }

    @Test
    fun `없는 토큰으로는 들어올 수 없다`() {
        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.accept("존재하지-않는-토큰", groomId)
        }

        assertEquals(GalleryErrorCode.INVITE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `담당 작가는 자기 갤러리 초대를 수락할 수 없다`() {
        // 멤버가 되면 "작가는 고객 대신 사진을 고를 수 없다"는 규칙을 스스로 우회하게 된다.
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.accept(tokenOf(invite), photographerId)
        }

        assertEquals(GalleryErrorCode.MANAGER_CANNOT_ACCEPT_INVITE, exception.errorCode)
    }

    @Test
    fun `다른 갤러리의 초대를 자기 갤러리 권한으로 폐기할 수 없다`() {
        val mine = saveGallery(ownerUserId = photographerId)
        val other = saveGallery(ownerUserId = 20L)
        val otherInvite = galleryInviteService.issue(galleryId(other), userId = 20L)

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.revoke(galleryId(mine), otherInvite.id, photographerId)
        }

        assertEquals(GalleryErrorCode.INVITE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `담당 작가가 아니면 링크를 폐기할 수 없다`() {
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        assertFailsWith<GalleryException> {
            galleryInviteService.revoke(galleryId(gallery), invite.id, userId = 999L)
        }
    }

    @Test
    fun `목록은 만료와 폐기를 구분해 함께 보여준다`() {
        // 걸러내면 작가가 "분명 발급했는데 없다"를 보게 되고, 폐기한 것인지 만료된 것인지
        // 새로 발급해야 하는지 화면에서 알 방법이 사라진다.
        val gallery = saveGallery()
        val active = galleryInviteService.issue(galleryId(gallery), photographerId)
        val revoked = galleryInviteService.issue(galleryId(gallery), photographerId)
        galleryInviteService.revoke(galleryId(gallery), revoked.id, photographerId)
        val expired = saveInvite(gallery, expiresAt = now.minusMinutes(1))

        val invites = galleryInviteService.list(galleryId(gallery), photographerId)

        assertEquals(3, invites.size)
        assertEquals(
            mapOf(
                active.id to GalleryInviteStatus.ACTIVE,
                revoked.id to GalleryInviteStatus.REVOKED,
                checkNotNull(expired.id) to GalleryInviteStatus.EXPIRED,
            ),
            invites.associate { it.id to it.status },
        )
    }

    @Test
    fun `폐기한 링크는 만료 시각이 지나도 폐기로 남는다`() {
        // 만료로 보이면 작가가 자기가 거둬들인 링크를 재발급해도 되는 것으로 읽는다.
        val gallery = saveGallery()
        val invite = saveInvite(gallery, expiresAt = now.minusMinutes(1))
        invite.revoke(now.minusHours(1))
        galleryInviteRepository.save(invite)

        val listed = galleryInviteService.list(galleryId(gallery), photographerId).single()

        assertEquals(GalleryInviteStatus.REVOKED, listed.status)
    }

    @Test
    fun `담당 작가가 아니면 목록을 볼 수 없다`() {
        val gallery = saveGallery()

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.list(galleryId(gallery), userId = 999L)
        }

        assertEquals(GalleryErrorCode.GALLERY_ACCESS_DENIED, exception.errorCode)
    }
}
