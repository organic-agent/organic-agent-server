package com.soma.wes.selection

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * 최종 선택 앨범을 HTTP 경계에서 확인한다.
 *
 * 보는 것은 셋이다: 계약 장수가 실제로 상한으로 작동하는지, 제출이 목록을 잠그는지,
 * 그리고 누가 무엇을 할 수 있는지(고르는 것은 부부, 되돌리는 것은 작가).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PhotoSelectionIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        photoSelectionItemRepository.deleteAllInBatch()
        photoSelectionRepository.deleteAllInBatch()
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `고른 사진과 남은 장수를 함께 돌려준다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        val photoIds = uploadPhotos(fixture, count = 3)

        select(fixture, photoIds.take(2))

        mockMvc.get(selectionUrl(fixture)) { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SELECTING") }
                jsonPath("$.targetPhotoCount") { value(3) }
                jsonPath("$.selectedCount") { value(2) }
                jsonPath("$.remainingCount") { value(1) }
                jsonPath("$.photos") { value(hasSize<Any>(2)) }
                jsonPath("$.photos[0].viewUrl") { value(containsString("X-Amz-Signature")) }
            }
    }

    @Test
    fun `아직 아무것도 고르지 않았으면 빈 앨범이 온다`() {
        // 조회가 앨범 행을 만들지 않는다. 만들면 갤러리를 열어보기만 한 사람 수만큼 빈 앨범이 쌓인다.
        val fixture = openGalleryWithMember(targetPhotoCount = 50)

        mockMvc.get(selectionUrl(fixture)) { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SELECTING") }
                jsonPath("$.selectedCount") { value(0) }
                jsonPath("$.remainingCount") { value(50) }
                jsonPath("$.photos") { value(hasSize<Any>(0)) }
            }

        assertEquals(0, photoSelectionRepository.count())
    }

    @Test
    fun `정확히 계약 장수만큼은 담긴다`() {
        // 경계값이 막히면 부부는 마지막 한 장을 영영 담지 못한다.
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        val photoIds = uploadPhotos(fixture, count = 3)

        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.selectedCount") { value(3) }
            jsonPath("$.remainingCount") { value(0) }
        }
    }

    @Test
    fun `계약 장수를 넘기면 한 장도 담기지 않는다`() {
        // 들어갈 수 있는 만큼만 담고 나머지를 버리면 화면에는 성공으로 보이고,
        // 어느 사진이 빠졌는지는 아무도 모른다.
        val fixture = openGalleryWithMember(targetPhotoCount = 2)
        val photoIds = uploadPhotos(fixture, count = 3)
        select(fixture, photoIds.take(1))

        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":${photoIds.drop(1)}}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("SELECTION_400_1") }
        }

        // 부분 성공이 없다는 것은 응답만으로는 확인되지 않는다.
        assertEquals(1, photoSelectionItemRepository.count())
    }

    @Test
    fun `계약 장수가 없으면 제한 없이 담는다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = null)
        val photoIds = uploadPhotos(fixture, count = 3)

        select(fixture, photoIds)

        mockMvc.get(selectionUrl(fixture)) { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.targetPhotoCount") { doesNotExist() }
                jsonPath("$.remainingCount") { doesNotExist() }
                jsonPath("$.selectedCount") { value(3) }
            }
    }

    @Test
    fun `이미 담긴 사진은 다시 담을 수 없다`() {
        // 신랑과 신부가 각자의 화면에서 고르므로, 겹쳤다는 것은 보고 있는 화면이 낡았다는 뜻이다.
        // 조용히 건너뛰면 신부는 자기가 방금 담았다고 생각한다.
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        val photoIds = uploadPhotos(fixture, count = 3)
        select(fixture, photoIds.take(2))

        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            // 한 장만 겹쳐도 통째로 막는다. 겹친 것만 빼고 담으면 화면과 실제가 더 벌어진다.
            content = """{"photoIds":${photoIds.drop(1)}}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SELECTION_409_3") }
        }

        assertEquals(2, photoSelectionItemRepository.count())
    }

    @Test
    fun `업로드가 끝나지 않은 사진은 고를 수 없다`() {
        // 실체가 없는 사진이 납품 목록에 섞이면, 작가는 목록에는 있는데 열리지 않는 항목을 받는다.
        val fixture = openGalleryWithMember(targetPhotoCount = 5)
        val pendingIds = issueUploadUrls(fixture, count = 1)

        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$pendingIds}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("SELECTION_400_5") }
        }
    }

    @Test
    fun `다른 갤러리의 사진은 고를 수 없다`() {
        // 갤러리 권한만 보고 사진 id를 믿으면, 자기 앨범으로 남의 사진을 끌어와 서명 URL까지 받아낸다.
        val fixture = openGalleryWithMember(targetPhotoCount = 5)
        val otherFixture = openGalleryWithMember(targetPhotoCount = 5)
        val otherPhotoIds = uploadPhotos(otherFixture, count = 1)

        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$otherPhotoIds}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("SELECTION_400_2") }
        }
    }

    @Test
    fun `한 장을 빼면 앨범에서만 빠진다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = 5)
        val photoIds = uploadPhotos(fixture, count = 2)
        select(fixture, photoIds)

        mockMvc.delete("${selectionUrl(fixture)}/photos/${photoIds.first()}") { authorize(fixture.member) }
            .andExpect { status { isNoContent() } }

        assertEquals(1, photoSelectionItemRepository.count())
        assertEquals(2, photoRepository.countByGalleryId(fixture.galleryId))
    }

    @Test
    fun `앨범에 없는 사진을 한 장 빼면 404`() {
        // 조용히 성공시키면 프론트는 지운 줄 알고 화면에서 지운다.
        val fixture = openGalleryWithMember(targetPhotoCount = 5)
        val photoIds = uploadPhotos(fixture, count = 2)
        select(fixture, photoIds.take(1))

        mockMvc.delete("${selectionUrl(fixture)}/photos/${photoIds.last()}") { authorize(fixture.member) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("SELECTION_404_1") }
            }
    }

    @Test
    fun `여러 장을 뺄 때는 이미 빠진 사진이 섞여 있어도 막지 않는다`() {
        // 화면이 조금 낡은 것뿐이라 통째로 거절하면 사용자는 어느 것이 문제인지 모른 채 다시 골라야 한다.
        val fixture = openGalleryWithMember(targetPhotoCount = 5)
        val photoIds = uploadPhotos(fixture, count = 3)
        select(fixture, photoIds.take(2))

        mockMvc.delete("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.selectedCount") { value(0) }
        }
    }

    @Test
    fun `제출하면 목록이 잠기고 작가가 되돌리면 다시 열린다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        val photoIds = uploadPhotos(fixture, count = 3)
        select(fixture, photoIds.take(2))

        mockMvc.post("${selectionUrl(fixture)}/submit") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUBMITTED") }
                // 계약 장수에 못 미쳐도 제출된다. 화면은 목표와 현재 장수를 보고 미리 물어본다.
                jsonPath("$.selectedCount") { value(2) }
                jsonPath("$.submittedAt") { exists() }
            }

        // 작가가 이 목록을 보고 보정에 들어가므로 그 뒤에 조용히 바뀌면 안 된다.
        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":${photoIds.drop(2)}}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SELECTION_409_1") }
        }
        mockMvc.delete("${selectionUrl(fixture)}/photos/${photoIds.first()}") { authorize(fixture.member) }
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("SELECTION_409_1") }
            }

        mockMvc.post("${selectionUrl(fixture)}/withdraw") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SELECTING") }
                jsonPath("$.submittedAt") { doesNotExist() }
            }

        select(fixture, photoIds.drop(2))
    }

    @Test
    fun `부부는 제출을 되돌릴 수 없다`() {
        // 부부가 스스로 되돌릴 수 있으면 제출이라는 잠금이 아무것도 잠그지 않는다.
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        select(fixture, uploadPhotos(fixture, count = 1))
        mockMvc.post("${selectionUrl(fixture)}/submit") { authorize(fixture.member) }
            .andExpect { status { isOk() } }

        mockMvc.post("${selectionUrl(fixture)}/withdraw") { authorize(fixture.member) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `제출되지 않은 앨범은 되돌릴 수 없다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        select(fixture, uploadPhotos(fixture, count = 1))

        mockMvc.post("${selectionUrl(fixture)}/withdraw") { authorize(fixture.photographer) }
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("SELECTION_409_2") }
            }
    }

    @Test
    fun `한 장도 고르지 않으면 제출할 수 없다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = 3)

        mockMvc.post("${selectionUrl(fixture)}/submit") { authorize(fixture.member) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("SELECTION_400_6") }
            }
    }

    @Test
    fun `작가는 고를 수 없고 보기만 한다`() {
        // 작가가 고객 대신 고르면 이 제품이 하는 일이 사라진다.
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        val photoIds = uploadPhotos(fixture, count = 2)

        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_1") }
        }

        mockMvc.get(selectionUrl(fixture)) { authorize(fixture.photographer) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `마감이 지나면 부부는 고를 수 없고 제출 결과는 계속 보인다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        val photoIds = uploadPhotos(fixture, count = 2)
        select(fixture, photoIds)
        passDeadline(fixture)

        mockMvc.delete("${selectionUrl(fixture)}/photos/${photoIds.first()}") { authorize(fixture.member) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_4") }
            }

        // 조회는 requireViewer 기준이라 마감 뒤에도 양쪽 모두 열린다 -- 마감됐다는 사실 자체를
        // 그 화면에서 알려줘야 하고, 작가는 결과를 보고 보정에 들어간다.
        mockMvc.get(selectionUrl(fixture)) { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.selectedCount") { value(2) }
            }
        mockMvc.get(selectionUrl(fixture)) { authorize(fixture.photographer) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `계약 장수는 작가만 정한다`() {
        val fixture = openGalleryWithMember(targetPhotoCount = null)

        mockMvc.patch("/api/v1/galleries/${fixture.galleryId}/target-photo-count") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetPhotoCount":10}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_1") }
        }

        mockMvc.patch("/api/v1/galleries/${fixture.galleryId}/target-photo-count") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetPhotoCount":10}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.targetPhotoCount") { value(10) }
        }
    }

    @Test
    fun `계약 장수를 0으로 정할 수 없다`() {
        // 막히는 것은 값을 넣은 작가가 아니라 아무것도 못 고르는 부부다.
        val fixture = openGalleryWithMember(targetPhotoCount = null)

        mockMvc.patch("/api/v1/galleries/${fixture.galleryId}/target-photo-count") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetPhotoCount":0}"""
        }.andExpect {
            status { isBadRequest() }
            // @Min이 컨트롤러에서 먼저 걸러 GLOBAL 코드가 나간다. 도메인의 GALLERY_400_3은
            // 서비스를 직접 부르는 경로를 위한 두 번째 방어선이다.
            jsonPath("$.code") { value("GLOBAL_400_2") }
        }
    }

    @Test
    fun `계약 장수가 줄어 이미 넘겼다면 남은 장수는 0이다`() {
        // 계약이 줄어드는 일은 실제로 있다. 그때 필요한 것은 작가 쪽의 400이 아니라
        // 부부에게 몇 장이 넘쳤는지 보여주는 화면이다.
        val fixture = openGalleryWithMember(targetPhotoCount = 3)
        select(fixture, uploadPhotos(fixture, count = 3))

        mockMvc.patch("/api/v1/galleries/${fixture.galleryId}/target-photo-count") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetPhotoCount":1}"""
        }.andExpect { status { isOk() } }

        mockMvc.get(selectionUrl(fixture)) { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.selectedCount") { value(3) }
                jsonPath("$.remainingCount") { value(0) }
            }
    }

    // --- helpers ---

    private data class Fixture(val photographer: User, val member: User, val galleryId: Long)

    private fun selectionUrl(fixture: Fixture) = "/api/v1/galleries/${fixture.galleryId}/photo-selection"

    /** 선택 앨범은 열린 갤러리의 부부가 쓰는 것이라, 매번 열린 갤러리와 멤버가 필요하다. */
    private fun openGalleryWithMember(targetPhotoCount: Int?): Fixture {
        val photographer = signUpPhotographer()
        val galleryId = createGallery(photographer, targetPhotoCount)

        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.open()
        galleryRepository.saveAndFlush(gallery)

        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = member.id!!))

        return Fixture(photographer, member, galleryId)
    }

    /**
     * 선택 마감을 과거로 밀어 부부의 작업을 잠근다.
     *
     * `changeSelectionDeadline`을 쓰지 않는다 — 지난 기한은 그쪽에서 막힌다. 여기서 필요한 것은
     * 시간이 흘러 기한이 지나버린 **상태**라 필드를 직접 세운다.
     */
    private fun passDeadline(fixture: Fixture) {
        val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
        gallery.selectionDeadline = ZonedDateTime.now().minusDays(1)
        galleryRepository.saveAndFlush(gallery)
    }

    private fun select(fixture: Fixture, photoIds: List<Long>) {
        mockMvc.post("${selectionUrl(fixture)}/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isOk() } }
    }

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "selection-$suffix",
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

    private fun createGallery(photographer: User, targetPhotoCount: Int?): Long {
        val target = targetPhotoCount?.let { ""","targetPhotoCount":$it""" } ?: ""
        val body = mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"본식"$target}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(body, "$.id").toLong()
    }

    /** 업로드 URL만 받아 PENDING으로 남겨둔다. 완료 통보를 보내지 않는 것이 이 헬퍼의 전부다. */
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
