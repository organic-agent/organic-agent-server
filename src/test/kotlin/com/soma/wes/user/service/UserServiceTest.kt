package com.soma.wes.user.service

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UserServiceTest @Autowired constructor(
    private val userService: UserService,
    private val userRepository: UserRepository,
) {

    @Test
    fun `id로 사용자 정보를 조회한다`() {
        val user = userRepository.save(
            User(
                provider = OAuthProvider.GOOGLE,
                providerId = "google-me-1",
                nickname = "테스터",
                email = "tester@example.com",
            ),
        )

        val response = userService.getUser(user.id!!)

        assertEquals(user.id, response.id)
        assertEquals(OAuthProvider.GOOGLE, response.provider)
        assertEquals("테스터", response.nickname)
        assertEquals("tester@example.com", response.email)
        assertEquals(Role.USER, response.role)
    }

    @Test
    fun `존재하지 않는 사용자를 조회하면 USER_NOT_FOUND를 던진다`() {
        val exception = assertFailsWith<UserException> { userService.getUser(-1L) }

        assertEquals(UserErrorCode.USER_NOT_FOUND, exception.errorCode)
    }
}
