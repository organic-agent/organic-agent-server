package com.soma.wes.auth.service

import com.soma.wes.auth.config.ReviewLoginProperties
import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.auth.dto.request.ReviewLoginRequest
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.TestClock
import com.soma.wes.user.domain.User
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import com.soma.wes.workspace.service.WorkspaceService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId

/**
 * 설정마다 컨텍스트를 새로 띄우지 않도록, 서비스는 공용 컨텍스트의 빈으로 직접 조립하고 설정만 바꿔 끼운다.
 */
@IntegrationTest
class ReviewLoginServiceTest @Autowired constructor(
    private val userFixture: UserFixture,
    private val userRepository: UserRepository,
    private val authTokenProvider: AuthTokenProvider,
    private val workspaceService: WorkspaceService,
    private val workspaceRepository: WorkspaceRepository,
) {

    private val today = LocalDate.of(2026, 11, 20)
    private val clock = TestClock(ZonedDateTime.of(today.atTime(12, 0), ZoneId.of("Asia/Seoul")))

    private fun service(properties: ReviewLoginProperties) =
        ReviewLoginService(properties, userRepository, authTokenProvider, workspaceService, clock)

    private fun openFor(user: User, expiresOn: LocalDate? = today) =
        ReviewLoginProperties(enabled = true, code = CODE, userId = user.requiredId, expiresOn = expiresOn)

    private fun assertNotFound(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(OAuthException::class.java)
            .extracting("errorCode")
            .isEqualTo(AuthErrorCode.REVIEW_LINK_NOT_FOUND)
    }

    @Test
    fun `맞는 코드면 심사용 계정의 토큰을 발급하고 개인 작업공간을 보장한다`() {
        // given
        val reviewer = userFixture.계정만("심사위원")

        // when
        val response = service(openFor(reviewer)).login(ReviewLoginRequest(CODE))

        // then
        val principal = authTokenProvider.getAuthUser(AccessToken(response.accessToken)).principal as LoginUser
        assertThat(principal.id).isEqualTo(reviewer.requiredId)
        assertThat(workspaceRepository.findByPersonalOwnerUserId(reviewer.requiredId)).isNotNull
    }

    @Test
    fun `만료일 당일까지는 열리고 다음 날부터 닫힌다`() {
        // given
        val reviewer = userFixture.사용자()

        // when & then
        service(openFor(reviewer, expiresOn = today)).login(ReviewLoginRequest(CODE))
        assertNotFound { service(openFor(reviewer, expiresOn = today.minusDays(1))).login(ReviewLoginRequest(CODE)) }
    }

    @Test
    fun `틀린 코드 꺼짐 짧은 코드 없는 계정은 모두 같은 404다`() {
        // given
        val reviewer = userFixture.사용자()

        // when & then
        assertNotFound { service(openFor(reviewer)).login(ReviewLoginRequest(CODE + "x")) }
        assertNotFound { service(openFor(reviewer).copy(enabled = false)).login(ReviewLoginRequest(CODE)) }
        assertNotFound { service(openFor(reviewer).copy(code = "short")).login(ReviewLoginRequest("short")) }
        assertNotFound { service(openFor(reviewer).copy(userId = 99_999_999)).login(ReviewLoginRequest(CODE)) }
    }

    companion object {
        private const val CODE = "review-code-0123456789"
    }
}
