package com.soma.wes.user.repository

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.user.domain.User
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class)
class UserRepositoryTest @Autowired constructor(
    private val userRepository: UserRepository,
) {

    @Test
    fun `provider와 providerId로 사용자를 조회한다`() {
        val saved = userRepository.save(
            User(provider = OAuthProvider.KAKAO, providerId = "kakao-1", nickname = "테스터"),
        )

        val found = userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "kakao-1")

        assertEquals(saved.id, found?.id)
    }

    @Test
    fun `providerId가 같아도 provider가 다르면 다른 사용자다`() {
        userRepository.save(
            User(provider = OAuthProvider.KAKAO, providerId = "same-id", nickname = "카카오"),
        )
        userRepository.save(
            User(provider = OAuthProvider.GOOGLE, providerId = "same-id", nickname = "구글"),
        )

        assertEquals("카카오", userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "same-id")?.nickname)
        assertEquals("구글", userRepository.findByProviderAndProviderId(OAuthProvider.GOOGLE, "same-id")?.nickname)
    }

    @Test
    fun `가입하지 않은 사용자를 조회하면 null을 반환한다`() {
        assertNull(userRepository.findByProviderAndProviderId(OAuthProvider.NAVER, "unknown"))
    }
}
