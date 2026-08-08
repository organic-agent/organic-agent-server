package com.soma.wes.photo

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.PhotoMetadata
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * 사진 한 장을 크게 보는 화면(#33)이 필요로 하는 것들을 HTTP 경계에서 확인한다.
 *
 * 목록과 다른 점이 셋이다: 원본 URL이 따로 오고, 촬영 정보가 붙고, 초대받은 부부도 볼 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PhotoDetailIntegrationTest @Autowired constructor(
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

    @Test
    fun `원본 URL과 파생본 URL이 각각 다른 키를 가리킨다`() {
        // 상세의 핵심이다. 목록용 URL은 파생 JPEG(줄어든 이미지)를 가리키므로 확대하면
        // 뭉개지고, 원본은 원래 크기지만 HEIC면 브라우저가 그리지 못한다. 둘 다 줘야 화면이 고른다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()
        val storageKey = attachPreview(photoId)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.previewReady") { value(true) }
                jsonPath("$.viewUrl") { value(containsString("previews/$storageKey")) }
                // 원본은 파생본으로 갈아타지 않는다.
                jsonPath("$.originalUrl") { value(containsString(storageKey)) }
                jsonPath("$.originalUrl") { value(not(containsString("previews/"))) }
                jsonPath("$.originalUrl") { value(containsString("X-Amz-Signature")) }
            }
    }

    @Test
    fun `원본 URL은 목록용보다 오래 산다`() {
        // 상세는 한 장을 오래 열어두는 화면이다. 목록과 같은 수명으로 서명하면 확대해 보는
        // 도중에 만료되고, 그때 S3는 403을 돌려주므로 사용자에게는 사진이 깨진 것처럼 보인다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.viewUrlTtlSeconds") { value(900) }
                jsonPath("$.originalUrlTtlSeconds") { value(3600) }
            }
    }

    @Test
    fun `파생본이 아직 없는 사진도 상세가 열린다`() {
        // 파생본은 임베딩 Lambda가 만든다. 그전까지 화면이 아무것도 못 여는 상태가 되면
        // 업로드 직후의 갤러리에서는 상세가 통째로 쓸모없어진다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        val body = mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") {
            authorize(fixture.photographer)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UPLOADED") }
            jsonPath("$.previewReady") { value(false) }
            // 촬영 정보도 임베딩 Lambda가 채운다. 아직 없으면 통째로 null이다 --
            // 빈 값만 가득한 객체를 주면 화면이 빈 칸을 늘어놓게 된다.
            jsonPath("$.metadata") { value(nullValue()) }
        }.andReturn().response.contentAsString

        // 파생본이 없으면 둘 다 원본을 가리킨다. 수명만 다르다.
        val viewUrl = JsonPath.read<String>(body, "$.viewUrl")
        val originalUrl = JsonPath.read<String>(body, "$.originalUrl")
        val storageKey = JsonPath.read<String>(body, "$.storageKey")
        assert(viewUrl.contains(storageKey)) { "파생본이 없으면 viewUrl도 원본을 가리켜야 한다: $viewUrl" }
        assert(originalUrl.contains(storageKey)) { "originalUrl은 언제나 원본을 가리킨다: $originalUrl" }
    }

    @Test
    fun `아직 올라오지 않은 사진은 URL을 주지 않는다`() {
        // PENDING은 서명 URL만 발급됐을 뿐 S3에 객체가 없을 수 있다. URL을 주면
        // 프론트의 <img>가 깨진 이미지를 그린다.
        val fixture = openGalleryWithMember()
        val photoId = issueUploadUrls(fixture, count = 1).first()

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("PENDING") }
                jsonPath("$.viewUrl") { value(nullValue()) }
                jsonPath("$.originalUrl") { value(nullValue()) }
            }
    }

    @Test
    fun `촬영 정보가 채워지면 상세에 함께 온다`() {
        // 실제로는 임베딩 Lambda가 벡터와 같은 UPDATE로 채운다. 여기서는 그 결과 상태를 만든다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.applyMetadata(
            PhotoMetadata(
                takenAt = LocalDateTime.of(2026, 5, 16, 14, 32, 10),
                cameraMake = "Apple",
                cameraModel = "iPhone 15 Pro",
                exposureTime = "1/200",
                fNumber = 2.8,
                iso = 400,
                width = 3024,
                height = 4032,
                byteSize = 2_411_984,
            ),
        )
        photoRepository.saveAndFlush(photo)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                // 타임존이 붙지 않는다. EXIF에 오프셋이 없어 벽시계 그대로 담기 때문이다 --
                // 서버 타임존으로 해석해 넣으면 여행지에서 찍은 사진이 조용히 옮겨간다.
                jsonPath("$.metadata.takenAt") { value("2026-05-16T14:32:10") }
                jsonPath("$.metadata.cameraModel") { value("iPhone 15 Pro") }
                jsonPath("$.metadata.exposureTime") { value("1/200") }
                jsonPath("$.metadata.fNumber") { value(2.8) }
                jsonPath("$.metadata.iso") { value(400) }
                jsonPath("$.metadata.width") { value(3024) }
                jsonPath("$.metadata.byteSize") { value(2_411_984) }
            }
    }

    @Test
    fun `초대받은 부부도 상세는 볼 수 있다`() {
        // 목록(원본 정리 화면)은 작가만 보지만, 클러스터·폴더에서 고른 한 장을 크게 보는 것은
        // 부부가 하는 일이다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(fixture.member) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `초대받지 않은 사람은 상세를 볼 수 없다`() {
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()
        val stranger = signUpUser()

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(stranger) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `다른 갤러리의 사진 id로는 상세를 열 수 없다`() {
        // 인가는 경로의 galleryId로 확인한다. 사진을 id만으로 찾으면 자기 갤러리 하나로
        // 남의 사진과 그 서명 URL까지 받아낼 수 있다.
        val fixture = openGalleryWithMember()
        val otherFixture = openGalleryWithMember()
        val otherPhotoId = uploadPhotos(otherFixture, count = 1).first()

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$otherPhotoId") { authorize(fixture.photographer) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("PHOTO_404_1") }
            }
    }

    @Test
    fun `집계 조회 경로가 사진 id로 읽히지 않는다`() {
        // /photos/{photoId}와 /photos/summary가 같은 자리를 두고 겹친다. 순서가 뒤집히면
        // summary 요청이 "summary라는 id"로 해석되어 400이 된다.
        val fixture = openGalleryWithMember()

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/summary") { authorize(fixture.photographer) }
            .andExpect { status { isOk() } }
    }

    // --- helpers ---

    private data class Fixture(val photographer: User, val member: User, val galleryId: Long)

    /** 상세는 작가와 부부 양쪽이 보므로 열린 갤러리와 멤버가 매번 필요하다. */
    private fun openGalleryWithMember(): Fixture {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.open()
        galleryRepository.saveAndFlush(gallery)

        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = member.id!!))

        return Fixture(photographer, member, galleryId)
    }

    /**
     * 임베딩 Lambda가 파생본을 올린 상태를 만들고 원본 키를 돌려준다.
     *
     * 파생본 키 규칙(`previews/{원본 키}`)은 Lambda의 `images.preview_key_for`가 정한다.
     */
    private fun attachPreview(photoId: Long): String {
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.previewKey = "previews/${photo.storageKey}"
        photoRepository.saveAndFlush(photo)
        return photo.storageKey
    }

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "photo-detail-$suffix",
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

    private fun MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }

    private fun createGallery(photographer: User): Long {
        val body = mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"본식"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(body, "$.id").toLong()
    }

    private fun issueUploadUrls(fixture: Fixture, count: Int): List<Long> {
        val files = (1..count).joinToString(",") {
            """{"fileName":"photo-$it.heic","contentType":"image/heic"}"""
        }

        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/upload-urls") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"files":[$files]}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        return JsonPath.read<List<Int>>(body, "$.uploads[*].photoId").map { it.toLong() }
    }

    /** 발급 + 완료 통보까지. 상세를 볼 수 있는 상태(UPLOADED)를 만든다. */
    private fun uploadPhotos(fixture: Fixture, count: Int): List<Long> {
        val photoIds = issueUploadUrls(fixture, count)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/complete") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isOk() } }

        return photoIds
    }
}
