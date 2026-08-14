package com.soma.wes.gallery.controller

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.repository.UserRepository
import org.hamcrest.Matchers.startsWith
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
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GalleryInviteControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val clock: Clock,
) {

    private val sequence = AtomicLong(System.nanoTime())

    private val now: ZonedDateTime get() = ZonedDateTime.now(clock)

    @BeforeEach
    fun clear() {
        galleryInviteRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `작가가 초대 링크를 발급하면 완성된 URL이 온다`() {
        // 토큰만 내려주면 도메인을 붙이는 규칙이 프론트 여러 곳에 흩어진다.
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)

        mockMvc.post("/api/v1/galleries/${gallery.id}/invites") { authorize(photographer) }
            .andExpect {
                status { isCreated() }
                jsonPath("$.id") { exists() }
                jsonPath("$.galleryId") { value(gallery.id!!) }
                jsonPath("$.status") { value("ACTIVE") }
                jsonPath("$.inviteUrl") { value(startsWith("http://localhost:3000/invite/")) }
                // 토큰은 링크 안에만 있고 따로 노출하지 않는다.
                jsonPath("$.token") { doesNotExist() }
            }
    }

    @Test
    fun `담당 작가가 아니면 링크를 발급할 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        val stranger = signUpPhotographer()

        mockMvc.post("/api/v1/galleries/${gallery.id}/invites") { authorize(stranger) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `현재 링크는 만료됐어도 상태와 함께 온다`() {
        // 만료를 걸러내면 작가가 "분명 발급했는데 없다"를 보게 되고, 다시 발급해야 하는
        // 상황인지 화면에서 알 방법이 사라진다.
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "expired-token", expiresAt = now.minusMinutes(1))

        mockMvc.get("/api/v1/galleries/${gallery.id}/invite") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("EXPIRED") }
            }
    }

    @Test
    fun `폐기만 해둔 갤러리는 현재 링크가 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "revoked-token", expiresAt = now.plusDays(7))
            .also { it.revoke(now); galleryInviteRepository.save(it) }

        mockMvc.get("/api/v1/galleries/${gallery.id}/invite") { authorize(photographer) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("GALLERY_404_3") }
            }
    }

    @Test
    fun `담당 작가가 아니면 현재 링크를 볼 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "some-token", expiresAt = now.plusDays(7))

        mockMvc.get("/api/v1/galleries/${gallery.id}/invite") { authorize(signUpUser()) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `정원이 차면 세 번째 사람은 403이다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "full-house", expiresAt = now.plusDays(7))
        mockMvc.post("/api/v1/invites/full-house/accept") { authorize(signUpUser()) }
        mockMvc.post("/api/v1/invites/full-house/accept") { authorize(signUpUser()) }

        mockMvc.post("/api/v1/invites/full-house/accept") { authorize(signUpUser()) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_5") }
            }
    }

    @Test
    fun `작가가 링크를 폐기하면 204이고 그 링크로는 들어올 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        val invite = saveInvite(gallery, token = "to-revoke", expiresAt = now.plusDays(7))

        mockMvc.delete("/api/v1/galleries/${gallery.id}/invites/${invite.id}") { authorize(photographer) }
            .andExpect { status { isNoContent() } }

        mockMvc.post("/api/v1/invites/to-revoke/accept") { authorize(signUpUser()) }
            .andExpect {
                // 우리가 발급한 링크가 맞으므로 404가 아니다. 410이어야 "작가에게 다시
                // 요청하세요"를 안내할 수 있다.
                status { isGone() }
                jsonPath("$.code") { value("GALLERY_410_2") }
            }
    }

    @Test
    fun `담당 작가가 아니면 링크를 폐기할 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        val invite = saveInvite(gallery, token = "not-yours", expiresAt = now.plusDays(7))
        val stranger = signUpPhotographer()

        mockMvc.delete("/api/v1/galleries/${gallery.id}/invites/${invite.id}") { authorize(stranger) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `링크를 누르면 갤러리 멤버가 되고 예비 부부로 온보딩된다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "welcome", expiresAt = now.plusDays(7))
        val groom = signUpUser()

        mockMvc.post("/api/v1/invites/welcome/accept") { authorize(groom) }
            .andExpect {
                status { isOk() }
                jsonPath("$.galleryId") { value(gallery.id!!) }
                jsonPath("$.memberId") { exists() }
            }

        assertNotNull(galleryMemberRepository.findByGalleryIdAndUserId(gallery.id!!, groom.id!!))
        assertEquals(UserType.CLIENT, userRepository.findById(groom.id!!).orElseThrow().userType)
    }

    @Test
    fun `같은 사람이 링크를 여러 번 눌러도 매번 성공하고 멤버는 하나다`() {
        // 두 번째에 에러를 주면 "링크가 잘못됐나" 싶게 만들 뿐이다.
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "twice", expiresAt = now.plusDays(7))
        val groom = signUpUser()

        repeat(2) {
            mockMvc.post("/api/v1/invites/twice/accept") { authorize(groom) }
                .andExpect { status { isOk() } }
        }

        assertEquals(1, galleryMemberRepository.findAllByGalleryId(gallery.id!!).size)
    }

    @Test
    fun `만료된 링크로는 들어올 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "too-late", expiresAt = now.minusMinutes(1))

        mockMvc.post("/api/v1/invites/too-late/accept") { authorize(signUpUser()) }
            .andExpect {
                status { isGone() }
                jsonPath("$.code") { value("GALLERY_410_1") }
            }
    }

    @Test
    fun `없는 토큰은 404`() {
        mockMvc.post("/api/v1/invites/never-issued/accept") { authorize(signUpUser()) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("GALLERY_404_3") }
            }
    }

    @Test
    fun `담당 작가는 자기 갤러리 초대를 수락할 수 없다`() {
        // 멤버가 되면 "작가는 고객 대신 사진을 고를 수 없다"는 규칙을 스스로 우회한다.
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "self-invite", expiresAt = now.plusDays(7))

        mockMvc.post("/api/v1/invites/self-invite/accept") { authorize(photographer) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_3") }
            }
    }

    @Test
    fun `로그인하지 않으면 수락할 수 없다`() {
        // 공개 경로로 열지 않는다. 누가 들어왔는지 GalleryMember에 남겨야 하기 때문이다.
        val photographer = signUpPhotographer()
        val gallery = saveGallery(photographer)
        saveInvite(gallery, token = "needs-login", expiresAt = now.plusDays(7))

        mockMvc.post("/api/v1/invites/needs-login/accept")
            .andExpect { status { isUnauthorized() } }
    }

    // --- helpers ---

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "invite-api-$suffix",
                nickname = "테스터",
                email = "invite-$suffix@example.com",
            ),
        )
    }

    private fun signUpPhotographer(): User {
        val user = signUpUser()
        val suffix = sequence.incrementAndGet()
        studioRepository.save(
            Studio(userId = user.id!!, name = "테스트 스튜디오", galleryUrl = "studio-$suffix"),
        )
        return user
    }

    private fun saveGallery(photographer: User): Gallery {
        val studioId = studioRepository.findByUserId(photographer.id!!)!!.id!!
        return galleryRepository.save(
            Gallery(studioId = studioId, title = "본식", status = GalleryStatus.OPEN),
        )
    }

    private fun saveInvite(gallery: Gallery, token: String, expiresAt: ZonedDateTime): GalleryInvite =
        galleryInviteRepository.save(
            GalleryInvite(galleryId = gallery.id!!, token = token, expiresAt = expiresAt),
        )

    private fun MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }
}
