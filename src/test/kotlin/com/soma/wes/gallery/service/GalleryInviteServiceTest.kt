package com.soma.wes.gallery.service

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(
    TestcontainersConfiguration::class,
    TimeConfig::class,
    GalleryAccessPolicy::class,
    GalleryInviteService::class,
    GalleryInviteTokenGenerator::class,
)
class GalleryInviteServiceTest @Autowired constructor(
    private val galleryInviteService: GalleryInviteService,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val galleryRepository: GalleryRepository,
    private val studioRepository: StudioRepository,
    private val clock: Clock,
) {

    private val now: ZonedDateTime get() = ZonedDateTime.now(clock)

    private val photographerId = 10L
    private val groomId = 100L
    private val brideId = 101L

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

    @Test
    fun `담당 작가는 초대 링크를 발급한다`() {
        val gallery = saveGallery()

        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        assertEquals(galleryId(gallery), invite.galleryId)
        assertTrue(invite.isUsableAt(now))
        assertTrue(invite.expiresAt.isAfter(now))
    }

    @Test
    fun `발급된 링크는 정해진 기간까지만 유효하다`() {
        val gallery = saveGallery()

        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        assertTrue(invite.isUsableAt(now.plus(GalleryInviteService.VALIDITY).minusMinutes(1)))
        assertTrue(invite.isExpiredAt(now.plus(GalleryInviteService.VALIDITY).plusMinutes(1)))
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

        val member = galleryInviteService.accept(invite.token, groomId)

        assertEquals(galleryId(gallery), member.galleryId)
        assertEquals(groomId, member.userId)
        assertNotNull(galleryMemberRepository.findByGalleryIdAndUserId(galleryId(gallery), groomId))
    }

    @Test
    fun `같은 링크를 신랑과 신부가 각각 쓸 수 있다`() {
        // 1회용이면 작가가 연락처도 모르는 신부 몫까지 따로 발급해야 한다.
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        galleryInviteService.accept(invite.token, groomId)
        galleryInviteService.accept(invite.token, brideId)

        assertEquals(2, galleryMemberRepository.findAllByGalleryId(galleryId(gallery)).size)
    }

    @Test
    fun `같은 사람이 링크를 여러 번 눌러도 멤버는 하나다`() {
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        val first = galleryInviteService.accept(invite.token, groomId)
        val second = galleryInviteService.accept(invite.token, groomId)

        assertEquals(first.id, second.id)
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

        galleryInviteService.revoke(galleryId(gallery), checkNotNull(invite.id), photographerId)

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.accept(invite.token, groomId)
        }
        assertEquals(GalleryErrorCode.INVITE_REVOKED, exception.errorCode)
    }

    @Test
    fun `링크를 폐기해도 이미 들어온 멤버는 남는다`() {
        // 폐기는 "더 들어오지 못하게" 하는 것이지 내보내는 동작이 아니다.
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)
        galleryInviteService.accept(invite.token, groomId)

        galleryInviteService.revoke(galleryId(gallery), checkNotNull(invite.id), photographerId)

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
            galleryInviteService.accept(invite.token, photographerId)
        }

        assertEquals(GalleryErrorCode.MANAGER_CANNOT_ACCEPT_INVITE, exception.errorCode)
    }

    @Test
    fun `다른 갤러리의 초대를 자기 갤러리 권한으로 폐기할 수 없다`() {
        val mine = saveGallery(ownerUserId = photographerId)
        val other = saveGallery(ownerUserId = 20L)
        val otherInvite = galleryInviteService.issue(galleryId(other), userId = 20L)

        val exception = assertFailsWith<GalleryException> {
            galleryInviteService.revoke(galleryId(mine), checkNotNull(otherInvite.id), photographerId)
        }

        assertEquals(GalleryErrorCode.INVITE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `담당 작가가 아니면 링크를 폐기할 수 없다`() {
        val gallery = saveGallery()
        val invite = galleryInviteService.issue(galleryId(gallery), photographerId)

        assertFailsWith<GalleryException> {
            galleryInviteService.revoke(galleryId(gallery), checkNotNull(invite.id), userId = 999L)
        }
    }
}
