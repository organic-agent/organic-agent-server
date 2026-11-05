package com.soma.wes.auth.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfoDto
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.TestSequence
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 소셜 로그인이 사용자 정보를 실제로 DB에 반영하는지 확인한다.
 *
 * 프로필 갱신은 더티 체킹에 기대고 있어서 **트랜잭션이 열려 있어야만** 저장된다. 한때
 * `login()`이 같은 클래스의 `process()`를 자기 호출하는 구조였는데, Spring의 프록시를
 * 거치지 않아 `@Transactional`이 조용히 무시됐고 이 갱신이 통째로 유실됐다.
 * 로그인은 성공하고 예외도 없어서 아무도 눈치채지 못했다.
 */
@IntegrationTest
class OAuthLoginProcessorTest @Autowired constructor(
    private val oAuthLoginProcessor: OAuthLoginProcessor,
    private val userRepository: UserRepository,
) {

    private fun userInfo(providerId: String, nickname: String, email: String?) = OAuthUserInfoDto(
        provider = OAuthProvider.KAKAO,
        providerId = providerId,
        nickname = nickname,
        email = email,
    )

    @Test
    fun `첫 로그인이면 가입시킨다`() {
        // given
        val providerId = "oauth-signup-${TestSequence.next()}"

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
    fun `다시 로그인하면 사용자 닉네임은 유지하고 소셜 이메일만 동기화한다`() {
        // given
        val providerId = "oauth-refresh-${TestSequence.next()}"
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
        assertThat(updated?.nickname).isEqualTo("옛 닉네임")
        assertThat(updated?.email).isEqualTo("new@example.com")
    }

    @Test
    fun `다시 로그인해도 사용자는 하나다`() {
        // given
        val providerId = "oauth-single-${TestSequence.next()}"

        // when
        oAuthLoginProcessor.process(userInfo(providerId, "사용자", "a@example.com"))
        oAuthLoginProcessor.process(userInfo(providerId, "사용자", "a@example.com"))

        // then
        assertThat(userRepository.findAll().count { it.providerId == providerId }).isEqualTo(1)
    }
}
