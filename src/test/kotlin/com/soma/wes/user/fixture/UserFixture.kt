package com.soma.wes.user.fixture

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.support.TestSequence
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.springframework.stereotype.Component

/**
 * 저장까지 마친 진짜 사용자 행을 만든다.
 *
 * `@TestComponent`가 아니라 `@Component`인 이유: `@TestComponent`는 스캔에서 제외되어
 * `@IntegrationTest`의 `@Import`에 올려야 하는데, 그러면 이 애노테이션을 쓰지 않는
 * 테스트들과 컨텍스트 설정이 갈라져 캐시가 쪼개진다. 테스트 소스의 `@Component`는
 * 스캔만으로 모든 테스트 컨텍스트에 등록되고, 프로덕션 클래스패스에는 존재하지 않는다.
 */
@Component
class UserFixture(
    private val userRepository: UserRepository,
) {

    fun 사용자(nickname: String = "테스터"): User {
        val suffix = TestSequence.next()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "fixture-$suffix",
                nickname = nickname,
                email = "fixture-$suffix@example.com",
            ),
        )
    }
}
