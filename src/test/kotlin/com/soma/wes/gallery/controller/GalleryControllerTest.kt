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
    fun `샘플 템플릿이 설정되지 않은 환경에서는 Mock 갤러리 생성을 503으로 막는다`() {
        // 기본 컨텍스트에는 app.mock-gallery.template-gallery-id가 없다(0 = 꺼짐).
        // 로컬이나 시드 전 운영이 이 상태다. 나머지 시나리오는 MockGalleryControllerTest에 있다.
        val photographer = signUpPhotographer()

        mockMvc.post("/api/v1/galleries/mock") {
            authorize(photographer)
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("GALLERY_503_1") }
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
    fun `이미 지난 마감 기한으로는 갤러리를 만들 수 없다`() {
        // 만들자마자 아무도 못 고르는 갤러리가 된다. 작가가 알아챌 수 있는 지점은 여기뿐이다.
        val photographer = signUpPhotographer()

        mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"본식","selectionDeadline":"2020-01-01T00:00:00+09:00"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("GALLERY_400_2") }
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

    @Test
    fun `작가가 갤러리를 열면 초대된 부부에게 보인다`() {
        // 여는 경로가 없으면 갤러리는 영원히 DRAFT로 남고, 멤버 행이 있어도 부부는 403만 받는다.
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "본식")
        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = gallery.id!!, userId = member.id!!))

        mockMvc.post("/api/v1/galleries/${gallery.id}/open") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("OPEN") }
            }

        mockMvc.get("/api/v1/galleries/${gallery.id}") { authorize(member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("본식") }
            }
    }

    @Test
    fun `담당 작가가 아니면 갤러리를 열 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "남의 갤러리")
        val other = signUpPhotographer()

        mockMvc.post("/api/v1/galleries/${gallery.id}/open") { authorize(other) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `이미 열린 갤러리는 다시 열 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "본식").also { it.open() }
        galleryRepository.save(gallery)

        mockMvc.post("/api/v1/galleries/${gallery.id}/open") { authorize(photographer) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("GALLERY_400_1") }
            }
    }

    @Test
    fun `마감해도 부부는 갤러리를 계속 볼 수 있다`() {
        // 마감은 선택을 멈추는 것이지 갤러리를 숨기는 것이 아니다. 마감됐다는 사실 자체를
        // 그 화면에서 알려줘야 한다.
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "본식").also { it.open() }
        galleryRepository.save(gallery)
        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = gallery.id!!, userId = member.id!!))

        mockMvc.post("/api/v1/galleries/${gallery.id}/close") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("CLOSED") }
            }

        mockMvc.get("/api/v1/galleries/${gallery.id}") { authorize(member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("CLOSED") }
            }
    }

    @Test
    fun `재오픈하면 마감 기한을 새로 받는다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "본식").also { it.open(); it.close() }
        galleryRepository.save(gallery)

        mockMvc.post("/api/v1/galleries/${gallery.id}/reopen") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"selectionDeadline":"2099-09-30T23:59:59+09:00"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.selectionDeadline") { exists() }
        }
    }

    @Test
    fun `이미 지난 기한으로는 재오픈할 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "본식").also { it.open(); it.close() }
        galleryRepository.save(gallery)

        mockMvc.post("/api/v1/galleries/${gallery.id}/reopen") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"selectionDeadline":"2020-01-01T00:00:00+09:00"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("GALLERY_400_2") }
        }

        mockMvc.get("/api/v1/galleries/${gallery.id}") { authorize(photographer) }
            .andExpect { jsonPath("$.status") { value("CLOSED") } }
    }

    @Test
    fun `마감된 적 없는 갤러리는 재오픈할 수 없다`() {
        val photographer = signUpPhotographer()
        val gallery = saveGallery(studioIdOf(photographer), "본식")

        mockMvc.post("/api/v1/galleries/${gallery.id}/reopen") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("GALLERY_400_1") }
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
