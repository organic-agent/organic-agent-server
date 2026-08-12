package com.soma.wes.photo

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.repository.PhotoRatingRepository
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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * 사진 별점을 HTTP 경계에서 확인한다.
 *
 * 핵심은 "점수가 사진당 하나"라는 것이다 — 부부 두 사람과 작가가 같은 한 칸을 나눠 쓰고,
 * 누가 매겼는지로 행이 나뉘지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PhotoRatingIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
    private val photoRatingRepository: PhotoRatingRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        photoRatingRepository.deleteAllInBatch()
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `부부가 별점을 매기면 상세에 실려 온다`() {
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        mockMvc.put(ratingUrl(fixture, photoId)) {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":4}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.score") { value(4) }
            jsonPath("$.ratedBy") { value(fixture.member.id!!.toInt()) }
        }

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.score") { value(4) }
            }
    }

    @Test
    fun `작가도 별점을 매긴다`() {
        // 추천작을 같은 자리에 표시한다. 부부의 점수와 작가의 점수가 따로 있지 않다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        mockMvc.put(ratingUrl(fixture, photoId)) {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":5}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.score") { value(5) }
            jsonPath("$.ratedBy") { value(fixture.photographer.id!!.toInt()) }
        }
    }

    @Test
    fun `다시 매기면 행이 늘지 않고 덮어써진다`() {
        // 사진당 한 행이 이 도메인의 전부다. 응답만 보면 행이 두 벌 쌓였는지 알 수 없다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()
        rate(fixture, photoId, fixture.member, score = 2)

        mockMvc.put(ratingUrl(fixture, photoId)) {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":5}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.score") { value(5) }
            // 마지막에 매긴 사람으로 바뀐다.
            jsonPath("$.ratedBy") { value(fixture.photographer.id!!.toInt()) }
        }

        assertEquals(1, photoRatingRepository.count())
    }

    @Test
    fun `범위를 벗어난 점수는 거부한다`() {
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        listOf(0, 6).forEach { score ->
            mockMvc.put(ratingUrl(fixture, photoId)) {
                authorize(fixture.member)
                contentType = MediaType.APPLICATION_JSON
                content = """{"score":$score}"""
            }.andExpect {
                status { isBadRequest() }
                // @Min·@Max가 컨트롤러에서 먼저 걸러 GLOBAL 코드가 나간다. 도메인의 PHOTO_400_4는
                // 서비스를 직접 부르는 경로를 위한 두 번째 방어선이다.
                jsonPath("$.code") { value("GLOBAL_400_2") }
            }
        }

        assertEquals(0, photoRatingRepository.count())
    }

    @Test
    fun `별점을 지운다`() {
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()
        rate(fixture, photoId, fixture.member, score = 3)

        mockMvc.delete(ratingUrl(fixture, photoId)) { authorize(fixture.member) }
            .andExpect { status { isNoContent() } }

        assertEquals(0, photoRatingRepository.count())
        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$photoId") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.score") { doesNotExist() }
            }
    }

    @Test
    fun `매긴 적 없는 별점을 지워도 성공한다`() {
        // 만들려는 상태(점수 없음)가 이미 그것이라 다시 보내도 결과가 같다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()

        mockMvc.delete(ratingUrl(fixture, photoId)) { authorize(fixture.member) }
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `다른 갤러리의 사진에는 매길 수 없다`() {
        // 인가는 경로의 galleryId로 끝난다. 사진을 id만으로 찾으면 그 확인이 무의미해진다.
        val fixture = openGalleryWithMember()
        val otherFixture = openGalleryWithMember()
        val otherPhotoId = uploadPhotos(otherFixture, count = 1).first()

        mockMvc.put(ratingUrl(fixture, otherPhotoId)) {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":5}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("PHOTO_404_1") }
        }
    }

    @Test
    fun `갤러리와 무관한 사용자는 매길 수 없다`() {
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()
        val stranger = signUpUser()

        mockMvc.put(ratingUrl(fixture, photoId)) {
            authorize(stranger)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":5}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_1") }
        }
    }

    @Test
    fun `마감이 지나면 부부는 매길 수 없고 작가는 매길 수 있다`() {
        // 마감은 고객이 고르는 기한이지 작가의 작업 기한이 아니다.
        val fixture = openGalleryWithMember()
        val photoId = uploadPhotos(fixture, count = 1).first()
        passDeadline(fixture)

        mockMvc.put(ratingUrl(fixture, photoId)) {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":5}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_4") }
        }

        mockMvc.put(ratingUrl(fixture, photoId)) {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":5}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `목록에 별점이 함께 오고 최소 점수로 거를 수 있다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 3)
        rate(fixture, photoIds[0], fixture.member, score = 5)
        rate(fixture, photoIds[1], fixture.member, score = 3)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.contents") { value(hasSize<Any>(3)) }
                jsonPath("$.contents[0].score") { value(5) }
                jsonPath("$.contents[1].score") { value(3) }
                // 아무도 매기지 않은 사진은 null이다.
                jsonPath("$.contents[2].score") { doesNotExist() }
            }

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos?minScore=4") {
            authorize(fixture.photographer)
        }.andExpect {
            status { isOk() }
            // 별점이 없는 사진도 함께 빠진다.
            jsonPath("$.contents") { value(hasSize<Any>(1)) }
            jsonPath("$.contents[0].photoId") { value(photoIds[0].toInt()) }
            jsonPath("$.totalCount") { value(1) }
        }
    }

    @Test
    fun `범위를 벗어난 minScore는 거부한다`() {
        // 조용히 빈 목록을 주면 화면에는 "고른 사진이 없다"로 보인다.
        val fixture = openGalleryWithMember()

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos?minScore=9") {
            authorize(fixture.photographer)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("PHOTO_400_4") }
        }
    }

    @Test
    fun `상태와 최소 점수를 함께 걸 수 있다`() {
        val fixture = openGalleryWithMember()
        val uploadedIds = uploadPhotos(fixture, count = 2)
        val pendingId = issueUploadUrls(fixture, count = 1).first()
        rate(fixture, uploadedIds[0], fixture.member, score = 5)
        rate(fixture, pendingId, fixture.member, score = 5)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos?status=UPLOADED&minScore=4") {
            authorize(fixture.photographer)
        }.andExpect {
            status { isOk() }
            jsonPath("$.contents") { value(hasSize<Any>(1)) }
            jsonPath("$.contents[0].photoId") { value(uploadedIds[0].toInt()) }
        }
    }

    // --- helpers ---

    private data class Fixture(val photographer: User, val member: User, val galleryId: Long)

    private fun ratingUrl(fixture: Fixture, photoId: Long) =
        "/api/v1/galleries/${fixture.galleryId}/photos/$photoId/rating"

    private fun rate(fixture: Fixture, photoId: Long, user: User, score: Int) {
        mockMvc.put(ratingUrl(fixture, photoId)) {
            authorize(user)
            contentType = MediaType.APPLICATION_JSON
            content = """{"score":$score}"""
        }.andExpect { status { isOk() } }
    }

    /** 별점은 requirePhotographerOrCouple을 지나므로 열린 갤러리와 멤버가 매번 필요하다. */
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

    /** 시간이 흘러 기한이 지나버린 상태가 필요한 것이라, 검증을 지나는 setter 대신 필드를 세운다. */
    private fun passDeadline(fixture: Fixture) {
        val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
        gallery.selectionDeadline = ZonedDateTime.now().minusDays(1)
        galleryRepository.saveAndFlush(gallery)
    }

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "rating-$suffix",
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

    /** 업로드 URL만 받아 PENDING으로 남겨둔다. */
    private fun issueUploadUrls(fixture: Fixture, count: Int): List<Long> {
        val files = (1..count).joinToString(",") {
            """{"fileName":"photo-${sequence.incrementAndGet()}.jpg","contentType":"image/jpeg"}"""
        }

        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/upload-urls") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"files":[$files]}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        return JsonPath.read<List<Int>>(body, "$.uploads[*].photoId").map { it.toLong() }
    }

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
