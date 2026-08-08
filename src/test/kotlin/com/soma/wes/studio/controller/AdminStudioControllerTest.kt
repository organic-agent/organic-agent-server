package com.soma.wes.studio.controller

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.repository.UserRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
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
        auditRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    @Test
    fun `ADMIN은 확인값을 제출해 스튜디오와 사진을 물리 삭제한다`() {
        val operator = signUp("deletion-admin", Role.ADMIN)
        val owner = signUp("deletion-owner")
        owner.selectType(UserType.PHOTOGRAPHER)
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

        repeat(2) {
            mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
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
            }
        }

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
