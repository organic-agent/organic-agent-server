package com.soma.wes.studio.controller

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.concurrent.atomic.AtomicLong

/**
 * 작가 온보딩을 HTTP 경계에서 확인한다.
 *
 * 이 API가 없으면 갤러리도 사진 업로드도 시작할 수 없다 — 갤러리가 작가 개인이 아니라
 * 스튜디오에 속하기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StudioControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `스튜디오를 만들면 온보딩이 끝난다`() {
        val user = signUp()

        mockMvc.post("/api/v1/studios") {
            authorize(user)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"오가닉 스튜디오","galleryUrl":"  Organic-Studio  ","inflowChannel":"인스타그램"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.name") { value("오가닉 스튜디오") }
            jsonPath("$.galleryUrl") { value("organic-studio") }
        }

        // 종류를 정하는 API를 따로 부르지 않았는데도 확정되어 있어야 한다.
        mockMvc.get("/api/v1/users/me") { authorize(user) }
            .andExpect { jsonPath("$.userType") { value("PHOTOGRAPHER") } }
    }

    @Test
    fun `이미 스튜디오가 있으면 409`() {
        val user = signUp()
        createStudio(user)

        mockMvc.post("/api/v1/studios") {
            authorize(user)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"두 번째","galleryUrl":"second-studio"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STUDIO_409_1") }
        }
    }

    @Test
    fun `이미 쓰는 주소면 409`() {
        createStudio(signUp(), galleryUrl = "taken-url")

        mockMvc.post("/api/v1/studios") {
            authorize(signUp())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"나중에 온 곳","galleryUrl":"  TAKEN-URL  "}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STUDIO_409_2") }
        }
    }

    @Test
    fun `쓸 수 없는 주소면 400`() {
        mockMvc.post("/api/v1/studios") {
            authorize(signUp())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"스튜디오","galleryUrl":"admin"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("STUDIO_400_1") }
        }
    }

    @Test
    fun `주소 중복을 미리 확인한다`() {
        val user = signUp()

        mockMvc.get("/api/v1/studios/gallery-url/availability") {
            authorize(user)
            param("galleryUrl", "  FREE-URL  ")
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.galleryUrl") { value("free-url") }
                jsonPath("$.available") { value(true) }
            }

        createStudio(user, galleryUrl = "free-url")

        mockMvc.get("/api/v1/studios/gallery-url/availability?galleryUrl=free-url") { authorize(user) }
            .andExpect {
                status { isOk() }
                jsonPath("$.available") { value(false) }
            }
    }

    @Test
    fun `확인 단계도 생성과 같은 규칙으로 거절한다`() {
        // 여기서 통과했는데 저장이 거절되면 사용자는 이유를 알 수 없다.
        mockMvc.get("/api/v1/studios/gallery-url/availability?galleryUrl=studio_url") { authorize(signUp()) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("STUDIO_400_1") }
            }
    }

    @Test
    fun `온보딩 전에는 내 스튜디오가 404다`() {
        // 프론트는 이 응답을 보고 스튜디오 생성 화면으로 보낸다.
        mockMvc.get("/api/v1/studios/me") { authorize(signUp()) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("STUDIO_404_1") }
            }
    }

    @Test
    fun `내 스튜디오를 조회하고 수정한다`() {
        val user = signUp()
        createStudio(user, name = "옛 이름", galleryUrl = "old-url")

        mockMvc.get("/api/v1/studios/me") { authorize(user) }
            .andExpect {
                status { isOk() }
                jsonPath("$.name") { value("옛 이름") }
            }

        mockMvc.patch("/api/v1/studios/me") {
            authorize(user)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"새 이름","galleryUrl":"  NEW-URL  "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("새 이름") }
            jsonPath("$.galleryUrl") { value("new-url") }
        }
    }

    @Test
    fun `토큰 없이는 스튜디오를 만들 수 없다`() {
        mockMvc.post("/api/v1/studios") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"스튜디오","galleryUrl":"no-token"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTHZ_401_1") }
        }
    }

    // --- helpers ---

    private fun signUp(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "studio-api-$suffix",
                nickname = "테스터",
                email = "tester-$suffix@example.com",
            ),
        )
    }

    private fun createStudio(user: User, name: String = "스튜디오", galleryUrl: String? = null) {
        val url = galleryUrl ?: "studio-${sequence.incrementAndGet()}"
        mockMvc.post("/api/v1/studios") {
            authorize(user)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","galleryUrl":"$url"}"""
        }.andExpect { status { isCreated() } }
    }

    private fun MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }
}
