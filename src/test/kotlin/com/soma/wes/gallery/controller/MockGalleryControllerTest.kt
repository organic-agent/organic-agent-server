package com.soma.wes.gallery.controller

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.GalleryType
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.config.MockGalleryProperties
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.service.MockGalleryService
import com.soma.wes.gallery.support.MockGalleryTemplate
import com.soma.wes.gallery.support.MockGalleryTemplateLoader
import com.soma.wes.gallery.support.MockGalleryTemplatePhoto
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.domain.PhotoStorageOwnership
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.domain.StudioDeletionClaim
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.studio.support.StudioDeletionProcessor
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    properties = [
        "app.mock-gallery.enabled=true",
        "app.mock-gallery.manifest-location=classpath:/mock-gallery/test-manifest.json",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class MockGalleryControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val mockGalleryService: MockGalleryService,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val claimRepository: StudioDeletionClaimRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
    private val objectMapper: ObjectMapper,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        claimRepository.deleteAllInBatch()
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `운영 v1 manifest는 서버 계약을 만족한다`() {
        val template = MockGalleryTemplateLoader(
            properties = MockGalleryProperties(
                enabled = true,
                manifestLocation = "classpath:/mock-gallery/v1.json",
            ),
            resourceLoader = DefaultResourceLoader(),
            objectMapper = objectMapper,
        ).load()

        assertEquals("v1", template.templateVersion)
        assertEquals("facebook/dinov2-base", template.embeddingModel)
        assertEquals(Photo.EMBEDDING_DIMENSION, template.embeddingDimension)
        assertEquals(80, template.photos.size)
        assertEquals((0 until 80).toList(), template.photos.map { it.displayOrder })
    }

    @Test
    fun `Mock 갤러리를 만들면 manifest의 공유 사진과 임베딩을 함께 seed한다`() {
        val photographer = signUpPhotographer()

        val response = createMockGalleryWithoutBody(photographer)

        assertEquals("MOCK", JsonPath.read<String>(response, "$.galleryType"))
        assertEquals("test-v1", JsonPath.read<String>(response, "$.templateVersion"))
        assertEquals(MockGalleryService.DEFAULT_TITLE, JsonPath.read<String>(response, "$.title"))
        val galleryId = JsonPath.read<Int>(response, "$.id").toLong()
        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        val photos = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(galleryId)

        assertEquals(GalleryType.MOCK, gallery.galleryType)
        assertEquals(1, photos.size)
        assertEquals(PhotoStorageOwnership.SHARED_TEMPLATE, photos.single().storageOwnership)
        assertEquals(PhotoStatus.EMBEDDED, photos.single().status)
        assertEquals(Photo.EMBEDDING_DIMENSION, photos.single().embedding?.size)
        assertNull(photos.single().uploadUrlExpiresAt)
        assertEquals("mock-gallery/test-v1/previews/sample-01.jpg", photos.single().viewKey)
    }

    @Test
    fun `현재 DB와 차원이 다른 manifest는 seed하지 않고 503으로 변환한다`() {
        val loader = loaderFor(validTemplate().copy(embeddingDimension = 512))

        val failure = assertFailsWith<GalleryException> { loader.load() }

        assertEquals(GalleryErrorCode.MOCK_GALLERY_NOT_READY, failure.errorCode)
    }

    @Test
    fun `DB 길이를 넘는 content type manifest도 503으로 변환한다`() {
        val template = validTemplate()
        val loader = loaderFor(
            template.copy(
                photos = template.photos.map { it.copy(contentType = "image/${"a".repeat(95)}") },
            ),
        )

        val failure = assertFailsWith<GalleryException> { loader.load() }

        assertEquals(GalleryErrorCode.MOCK_GALLERY_NOT_READY, failure.errorCode)
    }

    @Test
    fun `반복 요청은 최초 갤러리와 사진을 그대로 반환한다`() {
        val photographer = signUpPhotographer()

        val first = createMockGallery(photographer, "최초 제목")
        val second = createMockGallery(photographer, "무시할 제목")

        val firstId = JsonPath.read<Int>(first, "$.id").toLong()
        assertEquals(firstId, JsonPath.read<Int>(second, "$.id").toLong())
        assertEquals("최초 제목", JsonPath.read<String>(second, "$.title"))
        assertEquals(1, galleryRepository.count())
        assertEquals(1, photoRepository.count())
    }

    @Test
    fun `두 스튜디오는 같은 불변 샘플 key를 안전하게 공유한다`() {
        val first = signUpPhotographer()
        val second = signUpPhotographer()

        createMockGallery(first, "첫 스튜디오")
        createMockGallery(second, "둘째 스튜디오")

        val photos = photoRepository.findAll()
        assertEquals(2, photos.size)
        assertEquals(1, photos.map { it.storageKey }.distinct().size)
        assertEquals(2, photos.map { it.galleryId }.distinct().size)
    }

    @Test
    fun `동시 요청도 스튜디오당 하나만 만든다`() {
        val photographer = signUpPhotographer()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = (1..2).map { index ->
                executor.submit<Long> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    mockGalleryService.create(
                        photographer.id!!,
                        CreateGalleryRequest(title = "동시 요청 $index"),
                    ).id
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            val ids = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, ids.distinct().size)
            assertEquals(1, galleryRepository.count())
            assertEquals(1, photoRepository.count())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `삭제 claim이 있으면 Mock 갤러리와 공유 사진을 만들지 않는다`() {
        val photographer = signUpPhotographer()
        val studio = studioRepository.findByUserId(photographer.id!!)!!
        claimRepository.saveAndFlush(
            StudioDeletionClaim(
                requestId = UUID.randomUUID(),
                claimToken = UUID.randomUUID(),
                studioId = checkNotNull(studio.id),
                operatorUserId = 99L,
                studioGalleryUrl = studio.galleryUrl,
                reason = "Mock seed writer admission 회귀",
                planVersion = StudioDeletionProcessor.CURRENT_SUPPORTED_PLAN_VERSION,
            ),
        )

        mockMvc.post("/api/v1/galleries/mock") {
            authorize(photographer)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STUDIO_409_7") }
        }

        assertEquals(0, galleryRepository.count())
        assertEquals(0, photoRepository.count())
    }

    @Test
    fun `seed 직후 기존 클러스터 조회가 준비 완료 상태로 동작한다`() {
        val photographer = signUpPhotographer()
        val response = createMockGallery(photographer, "클러스터 체험")
        val galleryId = JsonPath.read<Int>(response, "$.id").toLong()

        mockMvc.get("/api/v1/galleries/$galleryId/photo-clusters") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.unclassified") { value(0) }
                jsonPath("$.clusters") { value(hasSize<Any>(1)) }
                jsonPath("$.clusters[0].size") { value(1) }
                jsonPath("$.clusters[0].photos[0].viewUrl") { value(containsString("X-Amz-Signature")) }
            }
    }

    @Test
    fun `force 임베딩도 공유 템플릿 벡터를 다시 계산하지 않는다`() {
        val photographer = signUpPhotographer()
        val response = createMockGallery(photographer, "임베딩 보호")
        val galleryId = JsonPath.read<Int>(response, "$.id").toLong()

        // 테스트에는 Lambda 설정이 없다. 공유 템플릿을 실행 대상으로 잘못 세면 503이 나가므로,
        // 202와 targets=0은 실행기를 부르기 전에 no-op으로 끝났다는 증거다.
        mockMvc.post("/api/v1/galleries/$galleryId/embeddings/run?force=true") {
            authorize(photographer)
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.targets") { value(0) }
        }
    }

    private fun createMockGallery(photographer: User, title: String): String =
        mockMvc.post("/api/v1/galleries/mock") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"$title"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

    private fun createMockGalleryWithoutBody(photographer: User): String =
        mockMvc.post("/api/v1/galleries/mock") {
            authorize(photographer)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

    private fun loaderFor(template: MockGalleryTemplate): MockGalleryTemplateLoader {
        val resource = ByteArrayResource(objectMapper.writeValueAsBytes(template))
        val resourceLoader = object : DefaultResourceLoader() {
            override fun getResource(location: String) = resource
        }
        return MockGalleryTemplateLoader(
            properties = MockGalleryProperties(
                enabled = true,
                manifestLocation = "classpath:/mock-gallery/in-memory.json",
            ),
            resourceLoader = resourceLoader,
            objectMapper = objectMapper,
        )
    }

    private fun validTemplate(): MockGalleryTemplate = MockGalleryTemplate(
        schemaVersion = 1,
        templateVersion = "validation-v1",
        embeddingModel = "facebook/dinov2-base",
        embeddingDimension = Photo.EMBEDDING_DIMENSION,
        photos = listOf(
            MockGalleryTemplatePhoto(
                storageKey = "mock-gallery/validation-v1/originals/sample.jpg",
                previewKey = "mock-gallery/validation-v1/previews/sample.jpg",
                originalFileName = "sample.jpg",
                contentType = "image/jpeg",
                displayOrder = 0,
                originalSha256 = "a".repeat(64),
                previewSha256 = "b".repeat(64),
                embedding = List(Photo.EMBEDDING_DIMENSION) { if (it == 0) 1f else 0f },
            ),
        ),
    )

    private fun signUpPhotographer(): User {
        val suffix = sequence.incrementAndGet()
        val user = userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "mock-gallery-$suffix",
                nickname = "테스터",
                email = "mock-gallery-$suffix@example.com",
            ),
        )
        studioRepository.save(
            Studio(
                userId = user.id!!,
                name = "Mock 스튜디오 $suffix",
                galleryUrl = "mock-studio-$suffix",
            ),
        )
        return user
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }
}
