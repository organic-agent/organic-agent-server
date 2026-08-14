package com.soma.wes.folder

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
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
 * 부모폴더-자식폴더-사진 구조의 생성·조회·수정·이동·삭제를 HTTP 경계에서 확인한다.
 *
 * 폴더는 예비 부부의 것이라, 여기 나오는 요청은 대부분 초대받은 멤버가 보낸다.
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
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        photoFolderItemRepository.deleteAllInBatch()
        photoFolderRepository.deleteAllInBatch()
        photoFolderGroupRepository.deleteAllInBatch()
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `클러스터링 결과를 부모폴더 하나로 고정한다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 4)

        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"name":"  본식  ","folders":[
                    {"name":"묶음 1","photoIds":${photoIds.take(2)}},
                    {"name":"묶음 2","photoIds":${photoIds.drop(2)}}
                ]}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            // 앞뒤 공백은 떼고 저장한다.
            jsonPath("$.name") { value("본식") }
            jsonPath("$.folders") { value(hasSize<Any>(2)) }
            jsonPath("$.folders[0].name") { value("묶음 1") }
            jsonPath("$.folders[0].photoCount") { value(2) }
            jsonPath("$.folders[0].coverPhoto.viewUrl") { value(containsString("X-Amz-Signature")) }
        }.andReturn().response.contentAsString

        val folderId = JsonPath.read<Int>(body, "$.folders[0].folderId").toLong()
        assertEquals(2, photoFolderItemRepository.countByFolderId(folderId))
    }

    @Test
    fun `묶음 간에 사진이 겹치면 전체가 거절되고 부모도 남지 않는다`() {
        // 같은 부모 아래 사진 중복 금지. 일부만 조용히 건너뛰면 성공처럼 보이는데
        // 무엇이 왜 빠졌는지 아무도 말할 수 없다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"name":"본식","folders":[
                    {"name":"묶음 1","photoIds":$photoIds},
                    {"name":"묶음 2","photoIds":[${photoIds.first()}]}
                ]}
            """.trimIndent()
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("FOLDER_409_1") }
        }

        // 검증이 저장보다 먼저라 이름뿐인 빈 부모가 남지 않는다.
        assertEquals(0, photoFolderGroupRepository.count())
    }

    @Test
    fun `빈 부모를 만들고 그 아래 빈 자식을 만든다`() {
        // 수동 흐름. 드래그로 채워 넣는 UX가 빈 폴더에서 시작한다.
        val fixture = openGalleryWithMember()
        val groupId = createGroup(fixture, "직접 만든 부모")

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"빈 폴더"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.groupId") { value(groupId.toInt()) }
            jsonPath("$.photos") { value(hasSize<Any>(0)) }
        }
    }

    @Test
    fun `자식폴더는 만든 시점의 목록을 고정한다`() {
        // 사진이 더 올라와도 이미 만든 폴더는 흔들리면 안 된다 -- 폴더는 클러스터를
        // 가리키는 포인터가 아니라 확정한 목록이다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

        uploadPhotos(fixture, count = 3)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$folderId") {
            authorize(fixture.member)
        }.andExpect {
            status { isOk() }
            jsonPath("$.photos") { value(hasSize<Any>(2)) }
        }
    }

    @Test
    fun `부모 목록은 자식 요약까지 한 번에 준다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 3)
        createGroupWithFolder(fixture, "첫 부모", "묶음", photoIds.take(1))
        createGroupWithFolder(fixture, "둘째 부모", "묶음", photoIds.drop(1))

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/folder-groups") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$") { value(hasSize<Any>(2)) }
                // 최근에 만든 부모가 먼저다.
                jsonPath("$[0].name") { value("둘째 부모") }
                jsonPath("$[0].folders[0].photoCount") { value(2) }
                jsonPath("$[1].folders[0].photoCount") { value(1) }
                // 좌측 폴더 메뉴를 이 응답 하나로 그린다.
                jsonPath("$[0].folders[0].coverPhoto.viewUrl") { value(containsString("X-Amz-Signature")) }
            }
    }

    @Test
    fun `같은 부모의 다른 자식에 이미 든 사진은 담을 수 없다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val (groupId, _) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds.take(1))
        val emptyFolderId = createFolder(fixture, groupId, "묶음 2", photoIds.drop(1))

        mockMvc.post(
            "/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$emptyFolderId/photos",
        ) {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[${photoIds.first()}]}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("FOLDER_409_1") }
        }

        // 전체 거절이라 항목 수가 그대로다.
        assertEquals(1, photoFolderItemRepository.countByFolderId(emptyFolderId))
    }

    @Test
    fun `서로 다른 부모끼리는 같은 사진을 담을 수 있다`() {
        // 중복 금지의 범위는 부모 하나다. 다른 부모는 서로 신경 쓸 필요가 없다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 1)
        createGroupWithFolder(fixture, "첫 부모", "묶음", photoIds)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"둘째 부모","folders":[{"name":"묶음","photoIds":$photoIds}]}"""
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `사진을 같은 부모의 다른 자식으로 옮긴다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 3)
        val (groupId, sourceId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)
        val targetId = createFolder(fixture, groupId, "묶음 2", emptyList())

        mockMvc.post(
            "/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$sourceId/photos/move",
        ) {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetFolderId":$targetId,"photoIds":${photoIds.take(2)}}"""
        }.andExpect {
            status { isOk() }
            // 응답은 사진이 도착한 폴더의 상세다.
            jsonPath("$.folderId") { value(targetId.toInt()) }
            jsonPath("$.photos") { value(hasSize<Any>(2)) }
        }

        assertEquals(1, photoFolderItemRepository.countByFolderId(sourceId))
        assertEquals(2, photoFolderItemRepository.countByFolderId(targetId))
    }

    @Test
    fun `출발지에 없는 사진은 옮길 수 없다`() {
        // 일부만 옮기면 성공처럼 보이는데 무엇이 빠졌는지 아무도 말할 수 없다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val (groupId, sourceId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds.take(1))
        val targetId = createFolder(fixture, groupId, "묶음 2", emptyList())

        mockMvc.post(
            "/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$sourceId/photos/move",
        ) {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetFolderId":$targetId,"photoIds":$photoIds}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("FOLDER_404_2") }
        }

        assertEquals(1, photoFolderItemRepository.countByFolderId(sourceId))
        assertEquals(0, photoFolderItemRepository.countByFolderId(targetId))
    }

    @Test
    fun `다른 부모의 자식으로는 옮길 수 없다`() {
        // 이동은 같은 부모를 공유하는 자식들 사이에서만 허용한다.
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val (groupId, sourceId) = createGroupWithFolder(fixture, "첫 부모", "묶음", photoIds.take(1))
        val (_, foreignFolderId) = createGroupWithFolder(fixture, "둘째 부모", "묶음", photoIds.drop(1))

        mockMvc.post(
            "/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$sourceId/photos/move",
        ) {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetFolderId":$foreignFolderId,"photoIds":[${photoIds.first()}]}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("FOLDER_404_1") }
        }
    }

    @Test
    fun `부모를 지우면 자식과 항목까지 사라지고 사진은 남는다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId") {
            authorize(fixture.member)
        }.andExpect { status { isNoContent() } }

        assertEquals(0, photoFolderGroupRepository.count())
        assertEquals(0, photoFolderRepository.count())
        assertEquals(0, photoFolderItemRepository.countByFolderId(folderId))
        // 사진 자체는 갤러리에 그대로 남는다.
        assertEquals(2, photoRepository.countByGalleryId(fixture.galleryId))
    }

    @Test
    fun `자식폴더를 지워도 부모와 사진은 남는다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$folderId") {
            authorize(fixture.member)
        }.andExpect { status { isNoContent() } }

        assertEquals(1, photoFolderGroupRepository.count())
        assertEquals(0, photoFolderItemRepository.countByFolderId(folderId))
        assertEquals(2, photoRepository.countByGalleryId(fixture.galleryId))
    }

    @Test
    fun `자식폴더에서 사진을 뺀다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 2)
        val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

        mockMvc.delete(
            "/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$folderId/photos/${photoIds.first()}",
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
        val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds.take(1))

        mockMvc.delete(
            "/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$folderId/photos/${photoIds.last()}",
        ) { authorize(fixture.member) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("FOLDER_404_2") }
            }
    }

    @Test
    fun `부모와 자식의 이름을 바꾼다`() {
        val fixture = openGalleryWithMember()
        val photoIds = uploadPhotos(fixture, count = 1)
        val (groupId, folderId) = createGroupWithFolder(fixture, "본식", "묶음 1", photoIds)

        mockMvc.patch("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"본식 (최종)"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("본식 (최종)") }
        }

        mockMvc.patch("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders/$folderId") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"신부 단독"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("신부 단독") }
            jsonPath("$.photoCount") { value(1) }
        }
    }

    @Test
    fun `다른 갤러리의 사진으로는 고정할 수 없다`() {
        // 갤러리 권한만 보고 사진 id를 믿으면, 자기 갤러리에 만든 폴더로 남의 사진을 끌어와
        // 서명 URL까지 받아낼 수 있다.
        val fixture = openGalleryWithMember()
        val otherFixture = openGalleryWithMember()
        val otherPhotoIds = uploadPhotos(otherFixture, count = 1)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"남의 사진","folders":[{"name":"묶음","photoIds":$otherPhotoIds}]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("FOLDER_400_1") }
        }
    }

    @Test
    fun `다른 갤러리의 부모폴더 id로는 접근할 수 없다`() {
        // 인가는 경로의 galleryId로 확인한다. 부모를 id만으로 찾으면 그 확인이 무의미해진다.
        val fixture = openGalleryWithMember()
        val otherFixture = openGalleryWithMember()
        val otherGroupId = createGroup(otherFixture, "남의 부모")

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/folder-groups/$otherGroupId") {
            authorize(fixture.member)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("FOLDER_404_3") }
        }
    }

    @Test
    fun `선택 마감이 지나면 부부는 폴더를 만들 수 없다`() {
        val fixture = openGalleryWithMember()
        uploadPhotos(fixture, count = 1)
        passDeadline(fixture)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"늦은 부모"}"""
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
        val (groupId, _) = createGroupWithFolder(fixture, "본식", "묶음", photoIds)
        passDeadline(fixture)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId") {
            authorize(fixture.photographer)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/folder-groups") { authorize(fixture.member) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_4") }
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

    /**
     * 선택 마감을 과거로 밀어 부부의 작업을 잠근다.
     *
     * `changeSelectionDeadline`을 쓰지 않는다 — 지난 기한은 그쪽에서 막힌다. 여기서 필요한 것은
     * "기한을 과거로 정하는 동작"이 아니라 시간이 흘러 기한이 지나버린 **상태**라, 시계를 돌리는
     * 대신 필드를 직접 세운다.
     */
    private fun passDeadline(fixture: Fixture) {
        val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
        gallery.selectionDeadline = ZonedDateTime.now().minusDays(1)
        galleryRepository.saveAndFlush(gallery)
    }

    private fun createGroup(fixture: Fixture, name: String): Long {
        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(body, "$.groupId").toLong()
    }

    /** 자식폴더 하나짜리 부모를 만들고 (groupId, folderId)를 돌려준다. */
    private fun createGroupWithFolder(
        fixture: Fixture,
        groupName: String,
        folderName: String,
        photoIds: List<Long>,
    ): Pair<Long, Long> {
        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups") {
            authorize(fixture.member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$groupName","folders":[{"name":"$folderName","photoIds":$photoIds}]}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(body, "$.groupId").toLong() to
            JsonPath.read<Int>(body, "$.folders[0].folderId").toLong()
    }

    private fun createFolder(fixture: Fixture, groupId: Long, name: String, photoIds: List<Long>): Long {
        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/folder-groups/$groupId/folders") {
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
