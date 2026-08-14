package com.soma.wes.studio.controller

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.domain.StudioDeletionClaimState
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.repository.UserRepository
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["app.studio-hard-deletion.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, AdminStudioControllerTest.StorageConfig::class)
class AdminStudioControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val auditRepository: StudioDeletionAuditRepository,
    private val claimRepository: StudioDeletionClaimRepository,
    private val photoStorage: PhotoStorage,
) {

    @TestConfiguration(proxyBeanMethods = false)
    class StorageConfig {

        @Bean
        @Primary
        fun photoStorage(): PhotoStorage = mock()
    }

    @BeforeEach
    fun clear() {
        reset(photoStorage)
        claimRepository.deleteAllInBatch()
        auditRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `ADMIN은 확인값을 제출해 스튜디오와 사진을 물리 삭제한다`() {
        val operator = signUp("deletion-admin", Role.ADMIN)
        val owner = signUp("deletion-owner")
        owner.selectPhotographerType()
        userRepository.saveAndFlush(owner)
        val studio = studioRepository.save(
            Studio(userId = checkNotNull(owner.id), name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val gallery = galleryRepository.save(Gallery(studioId = studioId, title = "본식"))
        val galleryId = checkNotNull(gallery.id)
        val photo = photoRepository.save(
            Photo(
                galleryId = galleryId,
                storageKey = "galleries/$galleryId/original.heic",
                originalFileName = "original.heic",
                contentType = "image/heic",
            ),
        )
        photo.previewKey = "previews/galleries/$galleryId/original.jpg"
        photoRepository.saveAndFlush(photo)
        val requestId = UUID.fromString("e64b9298-faa8-42c8-a108-3ef8587bc2f0")
        val auditIds = mutableListOf<Long>()

        repeat(2) {
            val body = mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
                authorize(operator)
                header("Idempotency-Key", requestId.toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"confirmedGalleryUrl":"organic-studio","reason":"문의 WES-CS-10 최종 확인"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.requestId") { value(requestId.toString()) }
                jsonPath("$.studioId") { value(studioId) }
                jsonPath("$.galleryCount") { value(1) }
                jsonPath("$.photoCount") { value(1) }
                jsonPath("$.objectCount") { value(2) }
            }.andReturn().response.contentAsString
            auditIds += JsonPath.read<Int>(body, "$.auditId").toLong()
        }

        assertEquals(1, auditIds.distinct().size)
        val keys = argumentCaptor<Collection<String>>()
        verify(photoStorage, times(1)).deleteAll(keys.capture())
        assertEquals(
            setOf("galleries/$galleryId/original.heic", "previews/galleries/$galleryId/original.jpg"),
            keys.firstValue.toSet(),
        )
        assertFalse(studioRepository.existsById(studioId))
        assertEquals(1, auditRepository.count())
        assertTrue(userRepository.existsById(checkNotNull(owner.id)))

        mockMvc.post("/api/v1/studios") {
            authorize(owner)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"다시 시작한 스튜디오","galleryUrl":"organic-reopened"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.galleryUrl") { value("organic-reopened") }
        }
    }

    @Test
    fun `같은 요청 ID를 동시에 실행해도 S3 삭제는 한 번만 수행한다`() {
        val operator = signUp("deletion-concurrent-admin", Role.ADMIN)
        val owner = signUp("deletion-concurrent-owner")
        val studio = studioRepository.saveAndFlush(
            Studio(userId = checkNotNull(owner.id), name = "동시 삭제 대상", galleryUrl = "concurrent-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.fromString("957949c6-f2a7-4c52-9e7b-07d7a087b619")
        val authorization = "Bearer ${authTokenProvider.generateAccessToken(operator).value}"
        val deletionStarted = CountDownLatch(1)
        val allowDeletion = CountDownLatch(1)
        doAnswer {
            deletionStarted.countDown()
            assertTrue(allowDeletion.await(5, TimeUnit.SECONDS))
            Unit
        }.whenever(photoStorage).deleteAll(any())

        fun executeDeletion(): MvcResult = mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
            header("Authorization", authorization)
            header("Idempotency-Key", requestId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"confirmedGalleryUrl":"concurrent-studio","reason":"문의 WES-CS-20 최종 확인"}"""
        }.andReturn()

        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable { executeDeletion() })
            assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))

            val duplicate = executor.submit(Callable { executeDeletion() }).get(5, TimeUnit.SECONDS)
            assertEquals(409, duplicate.response.status)
            assertEquals("STUDIO_409_6", JsonPath.read(duplicate.response.contentAsString, "$.code"))

            allowDeletion.countDown()
            val completed = first.get(5, TimeUnit.SECONDS)
            assertEquals(200, completed.response.status)
            val auditId = JsonPath.read<Int>(completed.response.contentAsString, "$.auditId").toLong()

            val repeated = executeDeletion()
            assertEquals(200, repeated.response.status)
            assertEquals(auditId, JsonPath.read<Int>(repeated.response.contentAsString, "$.auditId").toLong())
            verify(photoStorage, times(1)).deleteAll(any())
            assertEquals(1, auditRepository.count())
            assertEquals(0, claimRepository.count())
        } finally {
            allowDeletion.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `S3 삭제 시작 후 실패하면 같은 요청으로 재개한다`() {
        val operator = signUp("deletion-retry-admin", Role.ADMIN)
        val owner = signUp("deletion-retry-owner")
        val studio = studioRepository.saveAndFlush(
            Studio(userId = checkNotNull(owner.id), name = "재시도 대상", galleryUrl = "retryable-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val gallery = galleryRepository.saveAndFlush(Gallery(studioId = studioId, title = "본식"))
        photoRepository.saveAndFlush(
            Photo(
                galleryId = checkNotNull(gallery.id),
                storageKey = "galleries/${gallery.id}/retry.jpg",
                originalFileName = "retry.jpg",
                contentType = "image/jpeg",
            ),
        )
        val requestId = UUID.fromString("19ba37ce-f2f3-42c2-814a-c577fe51bb98")
        var deleteAttempts = 0
        doAnswer {
            if (deleteAttempts++ == 0) {
                throw PhotoException(PhotoErrorCode.STORAGE_DELETE_FAILED)
            }
            Unit
        }.whenever(photoStorage).deleteAll(any())

        fun executeDeletion(): MvcResult = mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
            authorize(operator)
            header("Idempotency-Key", requestId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"confirmedGalleryUrl":"retryable-studio","reason":"문의 WES-CS-21 최종 확인"}"""
        }.andReturn()

        val failed = executeDeletion()
        assertEquals(502, failed.response.status)
        assertEquals("PHOTO_502_2", JsonPath.read(failed.response.contentAsString, "$.code"))
        assertEquals(
            StudioDeletionClaimState.RETRYABLE,
            claimRepository.findById(requestId).orElseThrow().state,
        )

        val retried = executeDeletion()
        assertEquals(200, retried.response.status)
        assertFalse(studioRepository.existsById(studioId))
        assertEquals(0, claimRepository.count())
        verify(photoStorage, times(2)).deleteAll(any())
    }

    @Test
    fun `유효한 PUT URL이 남았으면 S3 호출 전에 삭제 준비를 롤백한다`() {
        val operator = signUp("upload-expiry-admin", Role.ADMIN)
        val owner = signUp("upload-expiry-owner")
        val studio = studioRepository.saveAndFlush(
            Studio(userId = checkNotNull(owner.id), name = "업로드 중", galleryUrl = "upload-expiry-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val gallery = galleryRepository.saveAndFlush(Gallery(studioId = studioId, title = "본식"))
        val photo = Photo(
            galleryId = checkNotNull(gallery.id),
            storageKey = "galleries/${gallery.id}/pending.jpg",
            originalFileName = "pending.jpg",
            contentType = "image/jpeg",
        )
        photo.recordUploadUrlExpiration(Instant.now().plusSeconds(300))
        photoRepository.saveAndFlush(photo)

        mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
            authorize(operator)
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"confirmedGalleryUrl":"upload-expiry-studio","reason":"문의 WES-CS-41 최종 확인"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STUDIO_409_8") }
        }

        verify(photoStorage, never()).deleteAll(any())
        assertEquals(0, claimRepository.count())
        assertTrue(studioRepository.existsById(studioId))
        assertEquals(1, photoRepository.count())
    }

    @Test
    fun `일반 사용자는 운영자 삭제 경로를 실행할 수 없다`() {
        val user = signUp("deletion-user")
        val owner = signUp("deletion-protected-owner")
        val studio = studioRepository.save(
            Studio(userId = checkNotNull(owner.id), name = "보호 대상", galleryUrl = "protected-studio"),
        )
        val studioId = checkNotNull(studio.id)

        mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
            authorize(user)
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"confirmedGalleryUrl":"protected-studio","reason":"실행하면 안 됨"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("AUTHZ_403_1") }
        }

        assertTrue(studioRepository.existsById(studioId))
        assertEquals(0, auditRepository.count())
    }

    private fun signUp(providerId: String, role: Role = Role.USER): User {
        val user = User(
            provider = OAuthProvider.KAKAO,
            providerId = providerId,
            nickname = "테스터",
            role = role,
        )
        return userRepository.saveAndFlush(user)
    }

    private fun MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }
}
