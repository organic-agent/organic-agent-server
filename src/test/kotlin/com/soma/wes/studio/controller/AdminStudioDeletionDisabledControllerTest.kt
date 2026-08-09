package com.soma.wes.studio.controller

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.domain.StudioDeletionAudit
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    AdminStudioDeletionDisabledControllerTest.StorageConfig::class,
)
class AdminStudioDeletionDisabledControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val auditRepository: StudioDeletionAuditRepository,
    private val claimRepository: StudioDeletionClaimRepository,
    private val photoStorage: PhotoStorage,
) {

    @BeforeEach
    fun clear() {
        reset(photoStorage)
        claimRepository.deleteAllInBatch()
        auditRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class StorageConfig {

        @Bean
        @Primary
        fun photoStorage(): PhotoStorage = mock()
    }

    @Test
    fun `기본 설정에서는 ADMIN 요청도 claim과 S3 전에 503으로 거절한다`() {
        val operator = userRepository.saveAndFlush(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "disabled-deletion-admin",
                nickname = "운영자",
                role = Role.ADMIN,
            ),
        )
        val owner = userRepository.saveAndFlush(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "disabled-deletion-owner",
                nickname = "소유자",
            ),
        )
        val studio = studioRepository.saveAndFlush(
            Studio(
                userId = checkNotNull(owner.id),
                name = "비활성 보호 대상",
                galleryUrl = "disabled-deletion-studio",
            ),
        )
        val studioId = checkNotNull(studio.id)

        mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
            header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(operator).value}")
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"confirmedGalleryUrl":"disabled-deletion-studio","reason":"문의 WES-CS-60 최종 확인"}"""
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("STUDIO_503_1") }
        }

        verify(photoStorage, never()).deleteAll(any())
        assertTrue(studioRepository.existsById(studioId))
        assertEquals(0, claimRepository.count())
        assertEquals(0, auditRepository.count())
    }

    @Test
    fun `기능을 끈 후에도 완료된 멱등 요청은 200으로 재생한다`() {
        val operator = userRepository.saveAndFlush(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "disabled-completed-admin",
                nickname = "운영자",
                role = Role.ADMIN,
            ),
        )
        val requestId = UUID.fromString("f0f36194-ab66-4cb9-8b94-3d8d291b61dd")
        val studioId = 999_001L
        val reason = "문의 WES-CS-62 최종 확인"
        val audit = auditRepository.saveAndFlush(
            StudioDeletionAudit(
                requestId = requestId,
                studioId = studioId,
                studioUserId = 777L,
                operatorUserId = checkNotNull(operator.id),
                studioGalleryUrl = "already-deleted-studio",
                reason = reason,
                galleryCount = 2,
                photoCount = 10,
                objectCount = 20,
            ),
        )

        mockMvc.post("/api/v1/admin/studios/$studioId/hard-delete") {
            header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(operator).value}")
            header("Idempotency-Key", requestId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"confirmedGalleryUrl":"already-deleted-studio","reason":"$reason"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.auditId") { value(audit.requiredId) }
            jsonPath("$.requestId") { value(requestId.toString()) }
            jsonPath("$.studioId") { value(studioId) }
        }

        verify(photoStorage, never()).deleteAll(any())
        assertEquals(0, claimRepository.count())
    }
}
