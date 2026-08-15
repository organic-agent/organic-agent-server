package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.atomic.AtomicLong

/**
 * 소셜 로그인이 사용자 정보를 실제로 DB에 반영하는지 확인한다.
 *
 * 프로필 갱신은 더티 체킹에 기대고 있어서 **트랜잭션이 열려 있어야만** 저장된다. 한때
 * `login()`이 같은 클래스의 `process()`를 자기 호출하는 구조였는데, Spring의 프록시를
 * 거치지 않아 `@Transactional`이 조용히 무시됐고 이 갱신이 통째로 유실됐다.
 * 로그인은 성공하고 예외도 없어서 아무도 눈치채지 못했다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OAuthLoginProcessorTest @Autowired constructor(
    private val oAuthLoginProcessor: OAuthLoginProcessor,
    private val userRepository: UserRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    private fun userInfo(providerId: String, nickname: String, email: String?) = OAuthUserInfo(
        provider = OAuthProvider.KAKAO,
        providerId = providerId,
        nickname = nickname,
        email = email,
    )

    @Test
    fun `첫 로그인이면 가입시킨다`() {
        // given
        val providerId = "oauth-signup-${sequence.incrementAndGet()}"

        // when
        val result = oAuthLoginProcessor.process(userInfo(providerId, "새 사용자", "new@example.com"))

        // then
        assertThat(result.response.accessToken).isNotNull()
        val saved = userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId)
        assertThat(saved?.nickname).isEqualTo("새 사용자")
        // 초대 수락을 이어서 하려면 호출부가 "방금 누가 로그인했는지"를 알아야 한다.
        assertThat(result.userId).isEqualTo(saved?.id)
    }

    @Test
    fun `다시 로그인하면 소셜에서 바뀐 프로필이 반영된다`() {
        // 트랜잭션이 열려 있지 않으면 조회된 엔티티가 준영속이라 이 갱신이 조용히 사라진다.
        // given
        val providerId = "oauth-refresh-${sequence.incrementAndGet()}"
        userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "옛 닉네임",
                email = "old@example.com",
            ),
        )

        // when
        oAuthLoginProcessor.process(userInfo(providerId, "새 닉네임", "new@example.com"))

        // then
        val updated = userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId)
        assertThat(updated?.nickname).isEqualTo("새 닉네임")
        assertThat(updated?.email).isEqualTo("new@example.com")
    }

    @Test
    fun `다시 로그인해도 사용자는 하나다`() {
        // given
        val providerId = "oauth-single-${sequence.incrementAndGet()}"

        // when
        oAuthLoginProcessor.process(userInfo(providerId, "사용자", "a@example.com"))
        oAuthLoginProcessor.process(userInfo(providerId, "사용자", "a@example.com"))

        // then
        assertThat(userRepository.findAll().count { it.providerId == providerId }).isEqualTo(1)
    }
}
