package com.soma.wes.auth.service.oauth

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * 로그인 진입점이 트랜잭션 경계를 제대로 넘는지 확인한다.
 *
 * 이 클래스가 존재하는 이유 자체가 트랜잭션이다. 한때 `login()`이 [OAuthLoginProcessor] 안에
 * 있으면서 같은 클래스의 `process()`를 자기 호출했는데, Spring의 프록시를 거치지 않아
 * `@Transactional`이 조용히 무시됐다. 그 결과 기존 사용자의 프로필 갱신이 더티 체킹을 타지
 * 못하고 통째로 유실됐다 — 로그인은 성공하고 예외도 없어서 드러나지 않았다.
 *
 * 그래서 `process()`를 직접 부르는 테스트로는 이 회귀를 잡을 수 없다. 반드시 [login]을 통해야 한다.
 * provider 왕복만 대역으로 세운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OAuthLoginServiceTest @Autowired constructor(
    private val oAuthLoginService: OAuthLoginService,
    private val userRepository: UserRepository,
) {

    @MockitoBean
    private lateinit var oAuthUserInfoService: OAuthUserInfoService

    private val sequence = AtomicLong(System.nanoTime())

    @Test
    fun `로그인하면 소셜에서 바뀐 프로필이 실제로 저장된다`() {
        val providerId = "oauth-login-${sequence.incrementAndGet()}"
        userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "옛 닉네임",
                email = "old@example.com",
            ),
        )

        whenever(oAuthUserInfoService.getUserInfo(eq(OAuthProvider.KAKAO), any(), anyOrNull())).thenReturn(
            OAuthUserInfo(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "새 닉네임",
                email = "new@example.com",
            ),
        )

        oAuthLoginService.login("kakao", AuthCodeRequest("auth-code"), "http://localhost:3000")

        // 트랜잭션이 열리지 않았다면 준영속 엔티티에 쓴 셈이라 옛 값이 그대로 남는다.
        val updated = userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId)
        assertEquals("새 닉네임", updated?.nickname)
        assertEquals("new@example.com", updated?.email)
    }

    @Test
    fun `첫 로그인이면 가입시키고 토큰을 준다`() {
        val providerId = "oauth-login-new-${sequence.incrementAndGet()}"
        whenever(oAuthUserInfoService.getUserInfo(eq(OAuthProvider.KAKAO), any(), anyOrNull())).thenReturn(
            OAuthUserInfo(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "새 사용자",
                email = "new@example.com",
            ),
        )

        val response = oAuthLoginService.login("kakao", AuthCodeRequest("auth-code"), null)

        assertEquals("새 사용자", userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId)?.nickname)
        assert(response.accessToken.isNotBlank())
    }
}
