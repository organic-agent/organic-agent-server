package com.soma.wes.gallery.service

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@Import(
    TestcontainersConfiguration::class,
    TimeConfig::class,
    GalleryAccessPolicy::class,
    GalleryMemberService::class,
)
class GalleryMemberServiceTest @Autowired constructor(
    private val galleryMemberService: GalleryMemberService,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val galleryRepository: GalleryRepository,
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
) {

    private var photographerId = 0L
    private var groomId = 0L
    private var brideId = 0L

    @BeforeEach
    fun setUpUsers() {
        photographerId = saveUser("photographer")
        groomId = saveUser("groom")
        brideId = saveUser("bride")
    }

    private fun saveUser(providerId: String): Long = userRepository.save(
        User(
            provider = OAuthProvider.KAKAO,
            providerId = providerId,
            nickname = providerId,
            email = "$providerId@example.com",
        ),
    ).requiredId

    private fun saveGallery(ownerUserId: Long = photographerId): Long {
        val studio = studioRepository.save(
            Studio(userId = ownerUserId, name = "스튜디오", galleryUrl = "studio-$ownerUserId"),
        )
        return galleryRepository.save(
            Gallery(studioId = studio.requiredId, title = "본식", status = GalleryStatus.OPEN),
        ).requiredId
    }

    private fun join(galleryId: Long, userId: Long): Long =
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = userId)).requiredId

    @Test
    fun `멤버 목록은 누구인지 알아볼 수 있게 준다`() {
        // memberId만 주면 작가가 둘 중 누구를 내보내야 하는지 가릴 수 없다.
        val galleryId = saveGallery()
        join(galleryId, groomId)

        val members = galleryMemberService.list(galleryId, photographerId).single()

        assertEquals(groomId, members.userId)
        assertEquals("groom", members.nickname)
        assertEquals("groom@example.com", members.email)
    }

    @Test
    fun `부부도 멤버 목록을 본다`() {
        // 파트너가 들어왔는지 확인하는 화면이다.
        val galleryId = saveGallery()
        join(galleryId, groomId)
        join(galleryId, brideId)

        val members = galleryMemberService.list(galleryId, groomId)

        assertEquals(setOf(groomId, brideId), members.map { it.userId }.toSet())
    }

    @Test
    fun `담당 작가는 멤버를 내보낸다`() {
        val galleryId = saveGallery()
        val memberId = join(galleryId, groomId)

        galleryMemberService.remove(galleryId, memberId, photographerId)

        assertNull(galleryMemberRepository.findByGalleryIdAndUserId(galleryId, groomId))
    }

    @Test
    fun `내보내면 자리가 비어 다른 사람이 들어올 수 있다`() {
        // 링크가 엉뚱한 사람에게 갔을 때 되돌리는 것이 이 기능의 목적이다.
        val galleryId = saveGallery()
        val strangerMemberId = join(galleryId, saveUser("stranger"))
        join(galleryId, groomId)

        galleryMemberService.remove(galleryId, strangerMemberId, photographerId)

        assertEquals(1, galleryMemberRepository.countByGalleryId(galleryId))
    }

    @Test
    fun `부부는 서로를 내보낼 수 없다`() {
        // 신랑이 신부를 지울 수 있으면 함께 고르라고 만든 갤러리가 아니게 된다.
        val galleryId = saveGallery()
        val brideMemberId = join(galleryId, brideId)
        join(galleryId, groomId)

        val exception = assertFailsWith<GalleryException> {
            galleryMemberService.remove(galleryId, brideMemberId, groomId)
        }

        assertEquals(GalleryErrorCode.GALLERY_ACCESS_DENIED, exception.errorCode)
        assertEquals(2, galleryMemberRepository.countByGalleryId(galleryId))
    }

    @Test
    fun `다른 갤러리의 멤버는 내보낼 수 없다`() {
        // 자기 갤러리 권한으로 남의 갤러리를 건드리지 못하게 한다.
        val myGalleryId = saveGallery()
        val otherGalleryId = saveGallery(saveUser("other-photographer"))
        val otherMemberId = join(otherGalleryId, groomId)

        val exception = assertFailsWith<GalleryException> {
            galleryMemberService.remove(myGalleryId, otherMemberId, photographerId)
        }

        assertEquals(GalleryErrorCode.MEMBER_NOT_FOUND, exception.errorCode)
        assertEquals(1, galleryMemberRepository.countByGalleryId(otherGalleryId))
    }

    @Test
    fun `없는 멤버를 내보내면 404다`() {
        val galleryId = saveGallery()

        val exception = assertFailsWith<GalleryException> {
            galleryMemberService.remove(galleryId, memberId = 999L, userId = photographerId)
        }

        assertEquals(GalleryErrorCode.MEMBER_NOT_FOUND, exception.errorCode)
    }
}
