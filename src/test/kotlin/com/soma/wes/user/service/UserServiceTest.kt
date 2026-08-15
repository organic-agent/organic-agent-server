package com.soma.wes.user.service

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UserServiceTest @Autowired constructor(
    private val userService: UserService,
    private val userRepository: UserRepository,
) {

    @Test
    fun `id로 사용자 정보를 조회한다`() {
        // given
        val user = userRepository.save(
            User(
                provider = OAuthProvider.GOOGLE,
                providerId = "google-me-1",
                nickname = "테스터",
                email = "tester@example.com",
            ),
        )

        // when
        val response = userService.getUser(user.id!!)

        // then
        assertSoftly { softly ->
            softly.assertThat(response.id).isEqualTo(user.id)
            softly.assertThat(response.provider).isEqualTo(OAuthProvider.GOOGLE)
            softly.assertThat(response.nickname).isEqualTo("테스터")
            softly.assertThat(response.email).isEqualTo("tester@example.com")
            softly.assertThat(response.role).isEqualTo(Role.USER)
        }
    }

    @Test
    fun `존재하지 않는 사용자를 조회하면 USER_NOT_FOUND를 던진다`() {
        // when & then
        assertThatThrownBy { userService.getUser(-1L) }
            .isInstanceOf(UserException::class.java)
            .extracting("errorCode")
            .isEqualTo(UserErrorCode.USER_NOT_FOUND)
    }
}
