package com.soma.wes.trash

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.dto.PresignedUploadDto
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.trash.repository.TrashRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 휴지통의 세 동작 — 목록·복원·즉시 물리 삭제 — 과 소프트 삭제의 가시성 규칙을 HTTP 경계에서
 * 확인한다.
 *
 * [PhotoStorage]는 기록형 가짜로 바꾼다. presign은 로컬 서명 연산이지만 deleteAll은 진짜
 * S3 API 호출이라 테스트에서 실행할 수 없고, 무엇보다 "무슨 키를 지웠는지"가 검증 대상이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TrashIntegrationTest.RecordingStorageConfig::class)
class TrashIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
    private val trashRepository: TrashRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val photoStorage: RecordingTrashPhotoStorage,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        // 스튜디오만 지우면 갤러리·사진·협업이 DB FK cascade로 함께 걷힌다. JPA 벌크 삭제와
        // 달리 DB cascade는 @SQLRestriction과 무관해 휴지통 행도 남지 않는다.
        studioRepository.deleteAllInBatch()
        photoStorage.reset()
    }

    // --- 사진 휴지통 ---

    @Test
    fun `사진을 휴지통으로 보내면 목록에서 사라지고 휴지통 목록에 나타난다`() {
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 3)
        val trashedId = photoIds.first()

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/photos") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":[$trashedId]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
        }

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.contents") { value(hasSize<Any>(2)) }
            }
        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/$trashedId") { authorize(fixture.photographer) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("PHOTO_404_1") }
            }
        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos/trash") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos") { value(hasSize<Any>(1)) }
                jsonPath("$.photos[0].photoId") { value(trashedId) }
                jsonPath("$.photos[0].expiresAt") { exists() }
                jsonPath("$.photos[0].viewUrl") { value(containsString("storage.test/view")) }
            }
    }

    @Test
    fun `휴지통 사진을 복원하면 목록에 돌아온다`() {
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 2)
        moveToTrash(fixture, photoIds)

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/trash/restore") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.contents") { value(hasSize<Any>(2)) }
            }
        assertTrue(trashRepository.findTrashedPhotos(fixture.galleryId).isEmpty())
    }

    @Test
    fun `휴지통에 없는 사진이 섞이면 복원 전체가 거절된다`() {
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 2)
        val trashedId = photoIds.first()
        moveToTrash(fixture, listOf(trashedId))

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/trash/restore") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("TRASH_404_1") }
        }

        // 전부-아니면-거부 — 섞인 요청은 휴지통에 있던 쪽도 되살리지 않는다.
        assertEquals(
            listOf(trashedId),
            trashRepository.findTrashedPhotos(fixture.galleryId).map { it.photoId },
        )
    }

    @Test
    fun `부부는 사진을 지우지도 되살리지도 못한다`() {
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 1)
        val member = inviteMember(fixture)

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/photos") {
            authorize(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isForbidden() } }

        mockMvc.post("/api/v1/galleries/${fixture.galleryId}/photos/trash/restore") {
            authorize(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `업로드 URL이 살아 있으면 즉시 삭제가 거절된다`() {
        // 발급 직후의 URL(30분)이 아직 유효하다. DB만 지우면 그 URL로 뒤늦게 올라온 객체가
        // 아무 행도 가리키지 않는 채 남는다.
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 1)
        moveToTrash(fixture, photoIds)

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/photos/trash") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("TRASH_409_1") }
        }

        assertEquals(0, photoStorage.deletedKeys().size)
    }

    @Test
    fun `사진 즉시 삭제는 원본과 파생 미리보기 키를 지우고 행을 걷는다`() {
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 1)
        val storageKey = photoRepository.findAllById(photoIds).single().storageKey
        expireUploadUrls(photoIds)
        moveToTrash(fixture, photoIds)

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/photos/trash") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isNoContent() } }

        // preview_key는 아직 null이라(임베딩 전) 원본과 파생 규칙 위치, 두 키다.
        assertEquals(
            setOf(storageKey, "previews/${storageKey.substringBeforeLast('.')}.jpg"),
            photoStorage.deletedKeys(),
        )
        assertEquals(0, countPhotoRows(photoIds.single()))
    }

    // --- 갤러리 휴지통 ---

    @Test
    fun `갤러리를 휴지통으로 보내면 안이 통째로 닫힌다`() {
        val fixture = createGalleryFixture()
        uploadPhotos(fixture, count = 2)
        val collabToken = openCollabSession(fixture)
        mockMvc.get("/api/v1/collab/$collabToken").andExpect { status { isOk() } }

        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}") { authorize(fixture.photographer) }
            .andExpect { status { isNoContent() } }

        // 갤러리 하나가 숨는 것으로 상세·사진 목록·하객 링크가 전부 404다.
        mockMvc.get("/api/v1/galleries/${fixture.galleryId}") { authorize(fixture.photographer) }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos") { authorize(fixture.photographer) }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/collab/$collabToken").andExpect { status { isNotFound() } }

        mockMvc.get("/api/v1/trash/galleries") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$") { value(hasSize<Any>(1)) }
                jsonPath("$[0].galleryId") { value(fixture.galleryId) }
                jsonPath("$[0].photoCount") { value(2) }
                jsonPath("$[0].expiresAt") { exists() }
            }
    }

    @Test
    fun `갤러리 복원은 지우기 전 모습 그대로 되살린다`() {
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 2)
        val trashedPhotoId = photoIds.first()
        moveToTrash(fixture, listOf(trashedPhotoId))
        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}") { authorize(fixture.photographer) }
            .andExpect { status { isNoContent() } }

        mockMvc.post("/api/v1/trash/galleries/${fixture.galleryId}/restore") { authorize(fixture.photographer) }
            .andExpect { status { isNoContent() } }

        // 갤러리보다 먼저 개별 삭제된 사진은 복원 뒤에도 휴지통에 남는다.
        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos") { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.contents") { value(hasSize<Any>(1)) }
            }
        assertEquals(
            listOf(trashedPhotoId),
            trashRepository.findTrashedPhotos(fixture.galleryId).map { it.photoId },
        )
    }

    @Test
    fun `갤러리 즉시 삭제는 휴지통에 있던 사진의 원본까지 걷는다`() {
        val fixture = createGalleryFixture()
        val photoIds = uploadPhotos(fixture, count = 2)
        val storageKeys = photoRepository.findAllById(photoIds).map { it.storageKey }
        expireUploadUrls(photoIds)
        moveToTrash(fixture, listOf(photoIds.first()))
        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}") { authorize(fixture.photographer) }
            .andExpect { status { isNoContent() } }

        mockMvc.delete("/api/v1/trash/galleries/${fixture.galleryId}") { authorize(fixture.photographer) }
            .andExpect { status { isNoContent() } }

        val deleted = photoStorage.deletedKeys()
        storageKeys.forEach { assertTrue(it in deleted, "$it 원본이 지워지지 않았다") }
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM galleries WHERE id = ?", Long::class.java, fixture.galleryId))
        photoIds.forEach { assertEquals(0, countPhotoRows(it)) }
    }

    @Test
    fun `남의 갤러리는 내 휴지통에서 보이지도 되살려지지도 않는다`() {
        val mine = createGalleryFixture()
        val others = createGalleryFixture()
        mockMvc.delete("/api/v1/galleries/${others.galleryId}") { authorize(others.photographer) }
            .andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/trash/galleries") { authorize(mine.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$") { value(hasSize<Any>(0)) }
            }
        mockMvc.post("/api/v1/trash/galleries/${others.galleryId}/restore") { authorize(mine.photographer) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("TRASH_404_2") }
            }
    }

    // --- helpers ---

    private data class Fixture(val photographer: User, val galleryId: Long)

    private fun createGalleryFixture(): Fixture {
        val photographer = signUpPhotographer()
        val body = mockMvc.post("/api/v1/galleries") {
            authorize(photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"본식"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return Fixture(photographer, JsonPath.read<Int>(body, "$.id").toLong())
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

    private fun moveToTrash(fixture: Fixture, photoIds: List<Long>) {
        mockMvc.delete("/api/v1/galleries/${fixture.galleryId}/photos") {
            authorize(fixture.photographer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"photoIds":$photoIds}"""
        }.andExpect { status { isOk() } }
    }

    /** 발급 시각 기준 30분짜리 URL을 이미 지난 것으로 만든다. 즉시 삭제의 409 가드를 지나기 위해서다. */
    private fun expireUploadUrls(photoIds: List<Long>) {
        val photos = photoRepository.findAllById(photoIds)
        photos.forEach { it.recordUploadUrlExpiration(Instant.now().minusSeconds(60)) }
        photoRepository.saveAllAndFlush(photos)
    }

    /** 하객 협업 링크의 토큰. 세션은 부부가 여는 것이라 멤버 초대와 갤러리 오픈이 선행된다. */
    private fun openCollabSession(fixture: Fixture): String {
        val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
        gallery.open()
        galleryRepository.saveAndFlush(gallery)
        val member = inviteMember(fixture)

        val body = mockMvc.post("/api/v1/galleries/${fixture.galleryId}/collab-sessions") {
            authorize(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"본식 후보"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<String>(body, "$.collabUrl").substringAfterLast('/')
    }

    private fun inviteMember(fixture: Fixture): User {
        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = fixture.galleryId, userId = member.id!!))
        return member
    }

    private fun countPhotoRows(photoId: Long): Int =
        checkNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM photos WHERE id = ?", Int::class.java, photoId))

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "trash-$suffix",
                nickname = "테스터",
                email = "tester-$suffix@example.com",
            ),
        )
    }

    private fun signUpPhotographer(): User {
        val user = signUpUser()
        studioRepository.save(
            Studio(userId = user.id!!, name = "테스트 스튜디오", galleryUrl = "studio-${sequence.incrementAndGet()}"),
        )
        return user
    }

    private fun MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RecordingStorageConfig {

        @Bean
        @Primary
        fun recordingTrashPhotoStorage(): RecordingTrashPhotoStorage = RecordingTrashPhotoStorage()
    }
}

/**
 * 호출을 기록하는 [PhotoStorage]. `buildKey`는 실제 구현과 같은 규칙을 쓴다 —
 * 지워진 키가 곧 검증 대상이라 임의 문자열로 대체할 수 없다. [TrashEraserTest]도 같이 쓴다.
 */
class RecordingTrashPhotoStorage : PhotoStorage {

    val deletedBatches = mutableListOf<List<String>>()

    /** true면 [deleteAll]이 실패한다. purge의 "다음 시각에 재시도" 경로를 확인할 때 쓴다. */
    var failDelete = false

    fun reset() {
        deletedBatches.clear()
        failDelete = false
    }

    fun deletedKeys(): Set<String> = deletedBatches.flatten().toSet()

    override fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "galleries/$galleryId/${UUID.randomUUID()}$suffix"
    }

    override fun presignUpload(key: String, contentType: String): PresignedUploadDto =
        PresignedUploadDto(url = "https://storage.test/upload/$key", expiresAt = Instant.now().plusSeconds(1800))

    override fun presignView(key: String): String = "https://storage.test/view/$key"

    override fun presignOriginal(key: String): String = "https://storage.test/original/$key"

    override fun deleteAll(keys: Collection<String>) {
        if (failDelete) {
            throw PhotoException(PhotoErrorCode.STORAGE_DELETE_FAILED)
        }
        deletedBatches += keys.toList()
    }

    override fun copy(sourceKey: String, targetKey: String) = Unit
}
