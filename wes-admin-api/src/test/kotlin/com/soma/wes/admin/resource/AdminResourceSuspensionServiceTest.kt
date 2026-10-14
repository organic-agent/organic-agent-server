package com.soma.wes.admin.resource

import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.resource.service.AdminResourceSuspensionService
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.auth.repository.AuthAccessStatusRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

@IntegrationTest
class AdminResourceSuspensionServiceTest @Autowired constructor(
    private val suspensionService: AdminResourceSuspensionService,
    private val resourceService: AdminResourceService,
    private val adminAccountFixture: AdminAccountFixture,
    private val jdbcClient: JdbcClient,
) {
    private val accessStatusRepository = AuthAccessStatusRepository(jdbcClient)

    @Test
    fun `사용자 정지는 refresh 토큰과 기존 access 권한을 즉시 막고 활성화하면 접근을 되살린다`() {
        val actor = adminAccountFixture.관리자("suspend-user")
        val created = resourceService.create(
            actor.requiredId,
            AdminResourceType.USER,
            CreateAdminResourceRequest("정지 테스트 사용자", mapOf(
                "provider" to "GOOGLE",
                "providerId" to "suspended-user",
                "nickname" to "정지 사용자",
            )),
            "127.0.0.1",
        )
        insertRefreshToken(created.id, "suspended-user-refresh")

        assertThatCode { accessStatusRepository.requireActive(created.id) }
            .doesNotThrowAnyException()

        val suspended = suspensionService.suspend(
            actor.requiredId, AdminResourceType.USER, created.id,
            ChangeAdminResourceStateRequest("부정 사용 조사", created.version), "127.0.0.1",
        )

        assertThat(suspended.fields["suspendedAt"]).isNotNull()
        assertThat(refreshTokenCount(created.id)).isZero()
        assertThatThrownBy { accessStatusRepository.requireActive(created.id) }
            .isInstanceOfSatisfying(TokenException::class.java) {
                assertThat(it.errorCode).isEqualTo(AuthErrorCode.USER_SUSPENDED)
            }

        val activated = suspensionService.activate(
            actor.requiredId, AdminResourceType.USER, created.id,
            ChangeAdminResourceStateRequest("조사 종료", suspended.version), "127.0.0.1",
        )
        assertThat(activated.fields["suspendedAt"]).isNull()
        assertThatCode { accessStatusRepository.requireActive(created.id) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `스튜디오 정지는 구성원 토큰만 회수하고 개인 워크스페이스 로그인은 유지한다`() {
        val actor = adminAccountFixture.관리자("suspend-studio")
        val user = resourceService.create(
            actor.requiredId, AdminResourceType.USER,
            CreateAdminResourceRequest("스튜디오 소유자", mapOf(
                "provider" to "NAVER", "providerId" to "studio-suspended", "nickname" to "스튜디오 소유자",
            )), "127.0.0.1",
        )
        val studio = resourceService.create(
            actor.requiredId, AdminResourceType.STUDIO,
            CreateAdminResourceRequest("정지할 스튜디오", mapOf(
                "ownerUserId" to user.id, "name" to "정지 스튜디오", "galleryUrl" to "suspended-studio",
            )), "127.0.0.1",
        )
        insertRefreshToken(user.id, "suspended-studio-refresh")

        assertThatCode { accessStatusRepository.requireActive(user.id) }
            .doesNotThrowAnyException()

        val suspended = suspensionService.suspend(
            actor.requiredId, AdminResourceType.STUDIO, studio.id,
            ChangeAdminResourceStateRequest("스튜디오 운영 정지", studio.version), "127.0.0.1",
        )

        assertThat(suspended.fields["suspendedAt"]).isNotNull()
        assertThat(refreshTokenCount(user.id)).isZero()
        assertThatCode { accessStatusRepository.requireActive(user.id) }
            .doesNotThrowAnyException()

        val activated = suspensionService.activate(
            actor.requiredId, AdminResourceType.STUDIO, studio.id,
            ChangeAdminResourceStateRequest("스튜디오 운영 재개", suspended.version), "127.0.0.1",
        )
        assertThat(activated.fields["suspendedAt"]).isNull()
        assertThatCode { accessStatusRepository.requireActive(user.id) }
            .doesNotThrowAnyException()
    }

    private fun insertRefreshToken(userId: Long, token: String) {
        jdbcClient.sql(
            """
            INSERT INTO refresh_tokens (user_id, token, expires_at, version, created_at, updated_at)
            VALUES (:userId, :token, CURRENT_TIMESTAMP + INTERVAL '1 day', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("token", token)
            .update()
    }

    private fun refreshTokenCount(userId: Long): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = :userId",
    ).param("userId", userId).query { rs, _ -> rs.getLong(1) }.single()
}
