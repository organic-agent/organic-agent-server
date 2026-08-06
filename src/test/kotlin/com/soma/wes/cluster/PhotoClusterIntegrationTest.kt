package com.soma.wes.cluster

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
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
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 유사도 임계값으로 사진을 묶는 경로를 HTTP 경계에서 확인한다.
 *
 * 벡터를 실제로 DB에 넣고 pgvector의 `<=>`로 거리를 재게 한다. 거리 계산을 흉내 내면
 * 이 기능에서 유일하게 어려운 부분(연산자 의미, 유사도와 거리의 뒤집힌 관계)이 검증에서 빠진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PhotoClusterIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    /**
     * 두 축이 만드는 평면 위의 단위 벡터. 두 벡터가 이루는 각의 코사인이 곧 코사인 유사도라,
     * 원하는 유사도를 각도로 바로 지정할 수 있다.
     */
    private fun vectorAt(radians: Double) = FloatArray(Photo.EMBEDDING_DIMENSION).also {
        it[0] = cos(radians).toFloat()
        it[1] = sin(radians).toFloat()
    }

    @Test
    fun `임계값 이상으로 닮은 사진끼리 한 묶음이 된다`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = uploadPhotos(photographer, galleryId, count = 3)

        // 0번과 1번은 유사도 0.95로 닮았고, 2번은 둘 모두와 직교한다(유사도 0).
        val closeAngle = acos(0.95)
        embed(photoIds[0], vectorAt(0.0))
        embed(photoIds[1], vectorAt(closeAngle))
        embed(photoIds[2], vectorAt(Math.PI / 2))

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters?threshold=0.9") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.threshold") { value(0.9) }
                jsonPath("$.unclassified") { value(0) }
                // 큰 묶음이 먼저 온다.
                jsonPath("$.clusters") { value(hasSize<Any>(2)) }
                jsonPath("$.clusters[0].size") { value(2) }
                jsonPath("$.clusters[1].size") { value(1) }
                jsonPath("$.clusters[1].photos[0].photoId") { value(photoIds[2].toInt()) }
            }
    }

    @Test
    fun `직접 닮지 않아도 사이에 낀 사진이 있으면 한 묶음이 된다`() {
        // 연결 요소로 묶는다는 결정이 겉으로 드러나는 자리다. 0-1과 1-2는 각각 0.95로 닮았지만
        // 0-2는 0.81까지 떨어진다. 그래도 셋은 같은 인물·장면일 가능성이 높다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = uploadPhotos(photographer, galleryId, count = 3)

        val step = acos(0.95)
        embed(photoIds[0], vectorAt(0.0))
        embed(photoIds[1], vectorAt(step))
        embed(photoIds[2], vectorAt(step * 2))

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters?threshold=0.9") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.clusters") { value(hasSize<Any>(1)) }
                jsonPath("$.clusters[0].size") { value(3) }
            }
    }

    @Test
    fun `임계값을 올리면 잘게 쪼개진다`() {
        // 임계값이 사용자에게 주는 손잡이라는 것을 보여주는 자리다. 같은 사진, 같은 벡터인데
        // 값만 바꿔도 결과가 통째로 달라진다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = uploadPhotos(photographer, galleryId, count = 3)

        val step = acos(0.95)
        embed(photoIds[0], vectorAt(0.0))
        embed(photoIds[1], vectorAt(step))
        embed(photoIds[2], vectorAt(step * 2))

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters?threshold=0.98") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.clusters") { value(hasSize<Any>(3)) }
                jsonPath("$.clusters[0].size") { value(1) }
            }
    }

    @Test
    fun `임계값을 생략하면 서버 기본값이 쓰이고 응답에 담겨 온다`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.threshold") { value(0.9) }
                jsonPath("$.clusters") { value(hasSize<Any>(0)) }
            }
    }

    @Test
    fun `임베딩이 없는 사진은 묶이지 않고 수로만 나온다`() {
        // 0이 아니면 임베딩 실행이 끝나지 않았다는 뜻이다. 프론트가 "아직 준비 중"을 안내할 근거다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = uploadPhotos(photographer, galleryId, count = 3)

        embed(photoIds[0], vectorAt(0.0))

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.clusters") { value(hasSize<Any>(1)) }
                jsonPath("$.unclassified") { value(2) }
            }
    }

    @Test
    fun `묶인 사진에는 서명된 조회 URL이 붙어 온다`() {
        // 비공개 버킷이라 이 URL 없이는 화면에 아무것도 그릴 수 없다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = uploadPhotos(photographer, galleryId, count = 1)
        embed(photoIds[0], vectorAt(0.0))

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.clusters[0].photos[0].viewUrl") {
                    value(org.hamcrest.Matchers.containsString("X-Amz-Signature"))
                }
            }
    }

    @Test
    fun `초대받은 부부도 클러스터를 볼 수 있다`() {
        // 작가만 보는 화면이 아니다. 고르는 것은 부부의 일이고, 묶음은 고르기 위한 화면이다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        openGallery(galleryId)

        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = member.id!!))

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters") { authorize(member) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `갤러리와 무관한 사용자는 볼 수 없다`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val stranger = signUpPhotographer()

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters") { authorize(stranger) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `임계값이 0에서 1 밖이면 400`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters?threshold=1.5") { authorize(photographer) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("CLUSTER_400_1") }
            }

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters?threshold=-0.1") { authorize(photographer) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("CLUSTER_400_1") }
            }
    }

    // --- helpers ---

    private fun embed(photoId: Long, vector: FloatArray) {
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.applyEmbedding(vector)
        photoRepository.saveAndFlush(photo)
    }

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "cluster-$suffix",
                nickname = "테스터",
                email = "tester-$suffix@example.com",
            ),
        )
    }

    private fun signUpPhotographer(): User {
        val user = signUpUser()
        val suffix = sequence.incrementAndGet()
        studioRepository.save(
            Studio(
                userId = user.id!!,
                name = "스튜디오 $suffix",
                galleryUrl = "studio-$suffix",
            ),
        )
        return user
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }

    private fun createGallery(photographer: User, title: String = "본식"): Long {
        val body = mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"$title"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(body, "$.id").toLong()
    }

    /** DRAFT인 갤러리는 멤버에게 보이지 않는다. 멤버가 등장하는 테스트는 먼저 열어야 한다. */
    private fun openGallery(galleryId: Long) {
        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.open()
        galleryRepository.saveAndFlush(gallery)
    }

    private fun uploadPhotos(photographer: User, galleryId: Long, count: Int): List<Long> {
        val files = (1..count).joinToString(",") {
            """{"fileName":"photo-$it.jpg","contentType":"image/jpeg"}"""
        }

        val body = mockMvc.post("/api/v1/galleries/$galleryId/photos/upload-urls") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"files":[$files]}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val photoIds = JsonPath.read<List<Int>>(body, "$.uploads[*].photoId").map { it.toLong() }

        mockMvc.post("/api/v1/galleries/$galleryId/photos/complete") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isOk() } }

        return photoIds
    }
}
