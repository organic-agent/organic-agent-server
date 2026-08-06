package com.soma.wes.gallery.controller

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GalleryControllerTest @Autowired constructor(
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
    fun `작가가 갤러리를 만들면 DRAFT로 시작한다`() {
        // 사진을 올리고 정리하는 동안 초대된 사람에게 보이면 안 된다. 여는 시점은 작가가 정한다.
        val photographer = signUpPhotographer()

        mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"김철수 · 이영희 본식"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.title") { value("김철수 · 이영희 본식") }
            jsonPath("$.status") { value("DRAFT") }
            jsonPath("$.selectionDeadline") { doesNotExist() }
        }
    }

    @Test
    fun `온보딩을 마치지 않은 사용자는 갤러리를 만들 수 없다`() {
        // 스튜디오가 없다는 것은 아직 작가가 아니라는 뜻이다. 갤러리는 작가 개인이 아니라
        // 스튜디오에 속하므로 붙일 곳 자체가 없다.
        val notOnboarded = signUpUser()

        mockMvc.post("/api/v1/galleries") {
            authorize(notOnboarded)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"갤러리"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("STUDIO_404_1") }
        }
    }

    @Test
    fun `제목이 비면 400`() {
        val photographer = signUpPhotographer()

        mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"  "}"""
        }.andExpect {
            // 잡아주지 않으면 클라이언트가 고칠 수 있는 잘못이 500(서버 장애)으로 나간다.
            status { isBadRequest() }
            jsonPath("$.code") { value("GLOBAL_400_2") }
        }
    }

    @Test
    fun `작가 목록에는 자기 스튜디오의 갤러리만 나온다`() {
        val mine = signUpPhotographer()
        val other = signUpPhotographer()
        saveGallery(studioIdOf(mine), "내 갤러리")
        saveGallery(studioIdOf(other), "남의 갤러리")

        mockMvc.get("/api/v1/galleries") { authorize(mine) }
            .andExpect {
                status { isOk() }
                jsonPath("$") { value(hasSize<Any>(1)) }
                jsonPath("$[0].title") { value("내 갤러리") }
            }
    }

    @Test
    fun `예비 부부 목록에는 아직 열리지 않은 갤러리가 나오지 않는다`() {
        val photographer = signUpPhotographer()
        val draft = saveGallery(studioIdOf(photographer), "정리 중")
        val opened = saveGallery(studioIdOf(photographer), "열린 갤러리").also { it.open() }
        galleryRepository.saveAll(listOf(draft, opened))

        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = draft.id!!, userId = member.id!!))
        galleryMemberRepository.save(GalleryMember(galleryId = opened.id!!, userId = member.id!!))

        mockMvc.get("/api/v1/galleries") { authorize(member) }
            .andExpect {
                status { isOk() }
                jsonPath("$") { value(hasSize<Any>(1)) }
                jsonPath("$[0].title") { value("열린 갤러리") }
            }
    }

    @Test
    fun `초대받지 않은 사용자는 갤러리를 조회할 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "남의 갤러리").also { it.open() }
        galleryRepository.save(gallery)

        val stranger = signUpUser()

        mockMvc.get("/api/v1/galleries/${gallery.id}") { authorize(stranger) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `없는 갤러리는 404`() {
        val photographer = signUpPhotographer()

        mockMvc.get("/api/v1/galleries/99999999") { authorize(photographer) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("GALLERY_404_1") }
            }
    }

    // --- helpers ---

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "gallery-api-$suffix",
                nickname = "테스터",
                email = "tester-$suffix@example.com",
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

    private fun studioIdOf(photographer: User): Long =
        studioRepository.findByUserId(photographer.id!!)!!.id!!

    private fun saveGallery(studioId: Long, title: String): Gallery =
        galleryRepository.save(Gallery(studioId = studioId, title = title))

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }
}
