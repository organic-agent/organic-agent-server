package com.soma.wes.gallery.controller

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GalleryMemberControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `작가는 멤버 목록에서 누구인지 알아본다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        val groom = signUpUser()
        join(gallery, groom)

        mockMvc.get("/api/v1/galleries/${gallery.id}/members") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].userId") { value(groom.requiredId) }
                jsonPath("$[0].nickname") { value(groom.nickname) }
            }
    }

    @Test
    fun `작가는 멤버를 내보낸다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        val member = join(gallery, signUpUser())

        mockMvc.delete("/api/v1/galleries/${gallery.id}/members/${member.id}") { authorize(photographer) }
            .andExpect { status { isNoContent() } }

        assertEquals(0, galleryMemberRepository.countByGalleryId(gallery.requiredId))
    }

    @Test
    fun `부부는 서로를 내보낼 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        val groom = signUpUser()
        val bride = signUpUser()
        join(gallery, groom)
        val brideMember = join(gallery, bride)

        mockMvc.delete("/api/v1/galleries/${gallery.id}/members/${brideMember.id}") { authorize(groom) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `이 갤러리의 멤버가 아니면 404다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)

        mockMvc.delete("/api/v1/galleries/${gallery.id}/members/999") { authorize(photographer) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("GALLERY_404_2") }
            }
    }

    @Test
    fun `로그인하지 않으면 목록을 볼 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)

        mockMvc.get("/api/v1/galleries/${gallery.id}/members")
            .andExpect { status { isUnauthorized() } }
    }

    // --- helpers ---

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "member-api-$suffix",
                nickname = "테스터-$suffix",
                email = "member-$suffix@example.com",
            ),
        )
    }

    private fun signUpPhotographer(): User {
        val user = signUpUser()
        val suffix = sequence.incrementAndGet()
        studioRepository.save(
            Studio(userId = user.requiredId, name = "테스트 스튜디오", galleryUrl = "studio-$suffix"),
        )
        return user
    }

    private fun saveGallery(photographer: User): Gallery {
        val studioId = studioRepository.findByUserId(photographer.requiredId)!!.requiredId
        return galleryRepository.save(
            Gallery(studioId = studioId, title = "본식", status = GalleryStatus.OPEN),
        )
    }

    private fun join(gallery: Gallery, user: User): GalleryMember =
        galleryMemberRepository.save(
            GalleryMember(galleryId = gallery.requiredId, userId = user.requiredId),
        )

    private fun MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }
}
