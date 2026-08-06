package com.soma.wes.photo

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
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

/**
 * 갤러리 생성 → 업로드 URL 발급 → 완료 통보 → 목록·집계 → 임베딩 실행까지, 이슈 #14가 만든
 * 파이프라인 전체를 HTTP 경계에서 확인한다.
 *
 * S3에 실제로 올리는 단계는 여기 없다. 그 단계는 프론트가 서명 URL로 직접 하고 서버는 관여하지
 * 않으므로, 서버 쪽에서 검증할 수 있는 것은 "서명이 붙은 URL을 제대로 내주는가"까지다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PhotoPipelineIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
) {

    // 컨텍스트(와 컨테이너)가 테스트 클래스 사이에서 재사용되므로 계정 식별자가 겹치면 안 된다.
    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `요청한 파일 수만큼 서명된 업로드 URL을 발급한다`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        mockMvc.post("/api/v1/galleries/$galleryId/photos/upload-urls") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"files":[
                  {"fileName":"DSC_0001.JPG","contentType":"image/jpeg"},
                  {"fileName":"DSC_0002.HEIC","contentType":"image/heic"}
                ]}
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.uploads") { value(hasSize<Any>(2)) }
            // 프론트가 이 URL로 S3에 직접 PUT 한다. 서명이 없으면 비공개 버킷이 거절한다.
            jsonPath("$.uploads[0].uploadUrl") { value(containsString("X-Amz-Signature")) }
            jsonPath("$.uploads[0].storageKey") { value(containsString("galleries/$galleryId/")) }
            // 원본 파일명은 컬럼에만 남는다. 키에 그대로 쓰면 중복·인코딩 문제가 생긴다.
            jsonPath("$.uploads[0].storageKey") { value(org.hamcrest.Matchers.not(containsString("DSC_0001"))) }
            jsonPath("$.uploadUrlTtlSeconds") { value(1800) }
        }
    }

    @Test
    fun `임베딩이 읽을 수 없는 형식은 발급 단계에서 막는다`() {
        // 여기서 막지 않으면 업로드는 전부 성공하고 임베딩만 조용히 실패해,
        // 사진이 영영 UPLOADED에 머무는 것으로만 드러난다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        mockMvc.post("/api/v1/galleries/$galleryId/photos/upload-urls") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"files":[{"fileName":"raw.arw","contentType":"image/x-sony-arw"}]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("PHOTO_400_2") }
        }
    }

    @Test
    fun `완료 통보를 받아야 임베딩 대상이 된다`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = issueUploadUrls(photographer, galleryId, count = 3)

        mockMvc.get("/api/v1/galleries/$galleryId/photos/summary") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.total") { value(3) }
                jsonPath("$.pending") { value(3) }
                jsonPath("$.uploaded") { value(0) }
            }

        mockMvc.post("/api/v1/galleries/$galleryId/photos/complete") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(3) }
        }

        mockMvc.get("/api/v1/galleries/$galleryId/photos/summary") { authorize(photographer) }
            .andExpect {
                jsonPath("$.pending") { value(0) }
                jsonPath("$.uploaded") { value(3) }
                jsonPath("$.embedded") { value(0) }
            }
    }

    @Test
    fun `올라온 사진에만 조회 URL이 붙는다`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = issueUploadUrls(photographer, galleryId, count = 2)
        completeUpload(photographer, galleryId, photoIds.take(1))

        mockMvc.get("/api/v1/galleries/$galleryId/photos") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos") { value(hasSize<Any>(2)) }
                jsonPath("$.totalCount") { value(2) }
                jsonPath("$.hasNext") { value(false) }
                // 버킷이 비공개라 이 URL이 브라우저가 이미지를 받을 유일한 통로다.
                jsonPath("$.photos[0].status") { value("UPLOADED") }
                jsonPath("$.photos[0].viewUrl") { value(containsString("X-Amz-Signature")) }
                // 아직 S3에 객체가 없는 사진에 URL을 주면 <img>가 깨진 이미지를 그린다.
                jsonPath("$.photos[1].status") { value("PENDING") }
                jsonPath("$.photos[1].viewUrl") { value(nullValue()) }
            }
    }

    @Test
    fun `파생본이 생기기 전에는 원본을 주고 준비되지 않았음을 알린다`() {
        // 임베딩 실행 전까지는 파생본이 없다. 원본이 HEIC라면 이 구간에서 미리보기가
        // 비어 보이는데, previewReady가 false라는 사실만으로 프론트가 그 사정을 안내할 수 있다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = issueUploadUrls(photographer, galleryId, count = 1)
        completeUpload(photographer, galleryId, photoIds)

        val storageKey = photoRepository.findById(photoIds.first()).orElseThrow().storageKey

        mockMvc.get("/api/v1/galleries/$galleryId/photos") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos[0].previewReady") { value(false) }
                jsonPath("$.photos[0].viewUrl") { value(containsString(storageKey)) }
            }
    }

    @Test
    fun `파생본이 있으면 조회 URL이 원본이 아니라 파생본을 가리킨다`() {
        // 아이폰 원본(HEIC)은 Chrome·Firefox·Edge가 디코딩하지 못한다. 임베딩 Lambda가
        // 만들어 둔 JPEG 파생본을 서명해 줘야 <img src>에 그대로 넣을 수 있다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = issueUploadUrls(photographer, galleryId, count = 1)
        completeUpload(photographer, galleryId, photoIds)

        val photo = photoRepository.findById(photoIds.first()).orElseThrow()
        val previewKey = "previews/${photo.storageKey.substringBeforeLast('.')}.jpg"
        photo.previewKey = previewKey
        photoRepository.saveAndFlush(photo)

        mockMvc.get("/api/v1/galleries/$galleryId/photos") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos[0].previewReady") { value(true) }
                jsonPath("$.photos[0].viewUrl") { value(containsString(previewKey)) }
                jsonPath("$.photos[0].viewUrl") { value(containsString("X-Amz-Signature")) }
                // storageKey는 그대로 원본을 가리킨다. 파생본은 화면용일 뿐 원본을 대신하지 않는다.
                jsonPath("$.photos[0].storageKey") { value(photo.storageKey) }
            }
    }

    @Test
    fun `상태로 걸러 받을 수 있다`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val photoIds = issueUploadUrls(photographer, galleryId, count = 3)
        completeUpload(photographer, galleryId, photoIds.take(2))

        mockMvc.get("/api/v1/galleries/$galleryId/photos?status=UPLOADED") { authorize(photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos") { value(hasSize<Any>(2)) }
                jsonPath("$.totalCount") { value(2) }
            }
    }

    @Test
    fun `페이지 크기 상한을 넘기면 400`() {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        mockMvc.get("/api/v1/galleries/$galleryId/photos?size=1001") { authorize(photographer) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("PHOTO_400_1") }
            }
    }

    @Test
    fun `다른 갤러리의 사진 id를 섞어 통보하면 404`() {
        // 갤러리 권한만 확인하고 id를 그대로 믿으면, 자기 갤러리 하나로 남의 사진 상태를 바꿀 수 있다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)
        val otherGalleryId = createGallery(photographer, title = "다른 갤러리")
        val otherPhotoIds = issueUploadUrls(photographer, otherGalleryId, count = 1)

        mockMvc.post("/api/v1/galleries/$galleryId/photos/complete") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$otherPhotoIds}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PHOTO_404_1") }
        }
    }

    @Test
    fun `초대된 멤버는 원본 목록을 볼 수 없다`() {
        // 원본은 작가가 정리하는 대상이다. 예비 부부가 만나는 것은 정리가 끝난 뒤의 화면이다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = member.id!!))

        mockMvc.get("/api/v1/galleries/$galleryId/photos") { authorize(member) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `남의 갤러리에는 업로드 URL을 발급받을 수 없다`() {
        val owner = signUpPhotographer()
        val galleryId = createGallery(owner)
        val stranger = signUpPhotographer()

        mockMvc.post("/api/v1/galleries/$galleryId/photos/upload-urls") {
            authorize(stranger)
            contentType = MediaType.APPLICATION_JSON
            content = """{"files":[{"fileName":"a.jpg","contentType":"image/jpeg"}]}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_1") }
        }
    }

    @Test
    fun `임베딩 함수가 설정되지 않았으면 기동이 아니라 호출 시점에 실패한다`() {
        // 로컬·테스트에는 Lambda가 없는 것이 정상이다. 없다고 앱을 못 뜨게 만들면 개발이 막힌다.
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer)

        mockMvc.post("/api/v1/galleries/$galleryId/embeddings/run") { authorize(photographer) }
            .andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.code") { value("PHOTO_503_1") }
            }
    }

    @Test
    fun `임베딩 실행도 담당 작가만 할 수 있다`() {
        val owner = signUpPhotographer()
        val galleryId = createGallery(owner)
        val stranger = signUpPhotographer()

        // 설정이 없어도(503) 권한 검사가 먼저다. 남의 갤러리라는 사실이 설정 상태에 가려지면 안 된다.
        mockMvc.post("/api/v1/galleries/$galleryId/embeddings/run") { authorize(stranger) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    // --- helpers ---

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "photo-pipeline-$suffix",
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

    private fun issueUploadUrls(photographer: User, galleryId: Long, count: Int): List<Long> {
        val files = (1..count).joinToString(",") {
            """{"fileName":"photo-$it.jpg","contentType":"image/jpeg"}"""
        }

        val body = mockMvc.post("/api/v1/galleries/$galleryId/photos/upload-urls") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"files":[$files]}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        return JsonPath.read<List<Int>>(body, "$.uploads[*].photoId").map { it.toLong() }
    }

    private fun completeUpload(photographer: User, galleryId: Long, photoIds: List<Long>) {
        mockMvc.post("/api/v1/galleries/$galleryId/photos/complete") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isOk() } }
    }
}
