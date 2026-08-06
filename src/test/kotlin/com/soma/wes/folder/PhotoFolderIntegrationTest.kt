package com.soma.wes.folder

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
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
 * 확정한 사진 묶음(폴더)의 생성·조회·수정·삭제를 HTTP 경계에서 확인한다.
 *
 * 폴더는 예비 부부의 것이라, 여기 나오는 요청은 전부 초대받은 멤버가 보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PhotoFolderIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        photoFolderItemRepository.deleteAllInBatch()
        photoFolderRepository.deleteAllInBatch()
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `클러스터 결과에 이름을 붙여 폴더로 저장한다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 3)

        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"  본식 - 신부 단독  ","photoIds":${photoIds.take(2)}}"""
        }.andExpect {
            status { isCreated() }
            // 앞뒤 공백은 떼고 저장한다.
            jsonPath("$.name") { value("본식 - 신부 단독") }
            jsonPath("$.photos") { value(hasSize<Any>(2)) }
            jsonPath("$.photos[0].viewUrl") { value(containsString("X-Amz-Signature")) }
        }.andReturn().response.contentAsString

        val folderId = JsonPath.read<Int>(body, "$.folderId").toLong()
        assertEquals(2, photoFolderItemRepository.countByFolderId(folderId))
    }

    @Test
    fun `폴더는 만든 시점의 목록을 고정한다`() {
        // 임계값이나 클러스터 식별자를 저장하지 않는 이유다. 사진이 더 올라와도 이미 만든
        // 폴더는 흔들리면 안 된다 -- 폴더는 클러스터의 스냅샷이 아니라 확정한 목록이다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val folderId = createFolder(fixture, "본식", photoIds)

        uploadPhotos(fixture, count = 3)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos") { value(hasSize<Any>(2)) }
            }
    }

    @Test
    fun `폴더 목록은 사진 없이 개수만 준다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 3)
        createFolder(fixture, "첫 번째", photoIds.take(1))
        createFolder(fixture, "두 번째", photoIds)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photo-folders") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$") { value(hasSize<Any>(2)) }
                // 최근에 만든 것이 먼저다.
                jsonPath("$[0].name") { value("두 번째") }
                jsonPath("$[0].photoCount") { value(3) }
                jsonPath("$[1].photoCount") { value(1) }
                // 카드 미리보기를 위해 폴더마다 상세를 따로 부르지 않아도 된다.
                jsonPath("$[0].coverPhoto.photoId") { value(photoIds.first().toInt()) }
                jsonPath("$[0].coverPhoto.viewUrl") { value(containsString("X-Amz-Signature")) }
                // 상세 조회와 같은 정렬이라 카드의 대표와 팝업의 첫 장이 어긋나지 않는다.
                jsonPath("$[1].coverPhoto.photoId") { value(photoIds.first().toInt()) }
            }
    }

    @Test
    fun `이름을 바꾼다`() {
        val fixture = openGalleryWithMember()
        val folderId = createFolder(fixture, "본식", uploadPhotos(fixture, count = 1))

        mockMvc.patch("/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"본식 (최종)"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("본식 (최종)") }
            jsonPath("$.photoCount") { value(1) }
        }
    }

    @Test
    fun `폴더를 지워도 사진은 갤러리에 남는다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val folderId = createFolder(fixture, "본식", photoIds)

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId") { authorize(fixture.member) }
            .andExpect { status { isNoContent() } }

        // 항목도 함께 지운다. 남겨 두면 어느 폴더에도 속하지 않은 행이 쌓인다.
        assertEquals(0, photoFolderItemRepository.countByFolderId(folderId))
        assertEquals(2, photoRepository.countByGalleryId(fixture.galleryId))
    }

    @Test
    fun `폴더에 사진을 더 담는다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 3)
        val folderId = createFolder(fixture, "본식", photoIds.take(1))

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":${photoIds.drop(1)}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.photos") { value(hasSize<Any>(3)) }
        }
    }

    @Test
    fun `이미 담긴 사진을 다시 담아도 늘어나지 않는다`() {
        // 유니크 제약이 마지막으로 막지만, 거기까지 가면 요청 전체가 실패한다.
        // 사용자가 보기에는 "몇 장은 이미 있다"일 뿐인 상황이다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val folderId = createFolder(fixture, "본식", photoIds)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.photos") { value(hasSize<Any>(2)) }
        }

        // 응답만 보면 중복 행이 생겼는지 알 수 없다. 응답은 사진을 조인해 돌려주므로
        // 항목이 두 벌 쌓여 있어도 같은 크기로 보인다.
        assertEquals(2, photoFolderItemRepository.countByFolderId(folderId))
    }

    @Test
    fun `사진을 하나도 지정하지 않으면 400`() {
        // DTO의 @NotEmpty는 컨트롤러를 지날 때만 도는 검증이라 서비스에서 한 번 더 막는다.
        // 통과시키면 사진 없는 폴더가 목록에 0장짜리로 남는다.
        val fixture = openGalleryWithMember()
        val folderId = createFolder(fixture, "본식", uploadPhotos(fixture, count = 1))

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId/photos") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("GLOBAL_400_2") }
        }
    }

    @Test
    fun `폴더에서 사진을 뺀다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val folderId = createFolder(fixture, "본식", photoIds)

        mockMvc.delete(
            "/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId/photos/${photoIds.first()}",
        ) { authorize(fixture.member) }
            .andExpect { status { isNoContent() } }

        assertEquals(1, photoFolderItemRepository.countByFolderId(folderId))
        // 폴더에서만 빠진다. 사진 자체는 갤러리에 그대로 있다.
        assertEquals(2, photoRepository.countByGalleryId(fixture.galleryId))
    }

    @Test
    fun `폴더에 없는 사진을 빼면 404`() {
        // 조용히 성공시키면 프론트는 지운 줄 알고 화면에서 지운다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val folderId = createFolder(fixture, "본식", photoIds.take(1))

        mockMvc.delete(
            "/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId/photos/${photoIds.last()}",
        ) { authorize(fixture.member) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("FOLDER_404_2") }
            }
    }

    @Test
    fun `다른 갤러리의 사진으로는 폴더를 만들 수 없다`() {
        // 갤러리 권한만 보고 사진 id를 믿으면, 자기 갤러리에 만든 폴더로 남의 사진을 끌어와
        // 서명 URL까지 받아낼 수 있다.
        val fixture = openGalleryWithMember()
        val otherFixture = openGalleryWithMember()
        val otherPhotoIds = uploadPhotos(otherFixture, count = 1)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"남의 사진","photoIds":$otherPhotoIds}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("FOLDER_400_1") }
        }
    }

    @Test
    fun `다른 갤러리의 폴더 id로는 접근할 수 없다`() {
        // 인가는 경로의 galleryId로 확인한다. 폴더를 id만으로 찾으면 그 확인이 무의미해진다.
        val fixture = openGalleryWithMember()
        val otherFixture = openGalleryWithMember()
        val otherFolderId = createFolder(otherFixture, "남의 폴더", uploadPhotos(otherFixture, count = 1))

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photo-folders/$otherFolderId") { authorize(fixture.member) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("FOLDER_404_1") }
            }
    }

    @Test
    fun `담당 작가도 자기 갤러리의 폴더를 만들 수 있다`() {
        // 고르는 것은 부부의 일이지만, 작가가 자기 갤러리에서 아무것도 못 하면
        // 폴더 기능을 확인할 방법이 없다. 작가는 스튜디오의 모든 기능을 쓴다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 1)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"작가가 만든 폴더","photoIds":$photoIds}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("작가가 만든 폴더") }
        }
    }

    @Test
    fun `선택 마감이 지나면 부부는 폴더를 만들 수 없다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 1)
        passDeadline(fixture)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"늦은 폴더","photoIds":$photoIds}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_4") }
        }
    }

    @Test
    fun `선택 마감이 지나도 작가는 폴더를 만질 수 있다`() {
        // 마감은 고객이 고르는 기한이지 작가의 작업 기한이 아니다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 1)
        val folderId = createFolder(fixture, "본식", photoIds)
        passDeadline(fixture)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photo-folders/$folderId") {
            authorize(fixture.photographer)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photo-folders") { authorize(fixture.member) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_4") }
            }
    }

    @Test
    fun `이름이 비어 있으면 400`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 1)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"   ","photoIds":$photoIds}"""
        }.andExpect {
            status { isBadRequest() }
            // @NotBlank가 컨트롤러에서 먼저 걸러 GLOBAL 코드가 나간다. 도메인의
            // FOLDER_400_3은 서비스를 직접 부르는 경로를 위한 두 번째 방어선이다.
            jsonPath("$.code") { value("GLOBAL_400_2") }
        }
    }

    // --- helpers ---

    private data class Fixture(val photographer: User, val member: User, val galleryId: Long)

    /** 폴더 API는 전부 requirePhotographerOrCouple을 지나므로, 열린 갤러리와 멤버가 매번 필요하다. */
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

    /** 선택 마감을 과거로 밀어 부부의 작업을 잠근다. */
    private fun passDeadline(fixture: Fixture) {
        val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
        gallery.changeSelectionDeadline(ZonedDateTime.now().minusDays(1))
        galleryRepository.saveAndFlush(gallery)
    }

    private fun createFolder(fixture: Fixture, name: String, photoIds: List<Long>): Long {
        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photo-folders") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","photoIds":$photoIds}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(body, "$.folderId").toLong()
    }

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "folder-$suffix",
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

    private fun createGallery(photographer: User, title: String = "본식"): Long {
        val body = mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"$title"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(body, "$.id").toLong()
    }

    private fun uploadPhotos(fixture: Fixture, count: Int): List<Long> {
        val files = (1..count).joinToString(",") {
            """{"fileName":"photo-${sequence.incrementAndGet()}.jpg","contentType":"image/jpeg"}"""
        }

        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/upload-urls") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"files":[$files]}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val photoIds = JsonPath.read<List<Int>>(body, "$.uploads[*].photoId").map { it.toLong() }

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/complete") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isOk() } }

        return photoIds
    }
}
