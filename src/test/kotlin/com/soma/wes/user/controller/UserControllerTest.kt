package com.soma.wes.user.controller

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class UserControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
) {

    private fun signUp(providerId: String) = userRepository.save(
        User(
            provider = OAuthProvider.KAKAO,
            providerId = providerId,
            nickname = "테스터",
            email = "tester@example.com",
        ),
    )

    @Test
    fun `access token으로 내 정보를 조회한다`() {
        val user = signUp("kakao-me-1")
        val accessToken = authTokenProvider.generateAccessToken(user)

        mockMvc.get("/api/v1/users/me") {
            header("Authorization", "Bearer ${accessToken.value}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(user.id!!) }
            // 응답 표기도 요청 표기(OAuthProvider.key)와 같아야 한다. enum 이름이 새어 나가면 안 된다.
            jsonPath("$.provider") { value("kakao") }
            jsonPath("$.nickname") { value("테스터") }
            jsonPath("$.email") { value("tester@example.com") }
            jsonPath("$.role") { value("USER") }
            jsonPath("$.createdAt") { exists() }
        }
    }

    @Test
    fun `토큰 없이 조회하면 401을 응답한다`() {
        mockMvc.get("/api/v1/users/me")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("AUTHZ_401_1") }
            }
    }

    @Test
    fun `refresh token으로는 조회할 수 없다`() {
        val user = signUp("kakao-me-2")
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        mockMvc.get("/api/v1/users/me") {
            header("Authorization", "Bearer ${refreshToken.value}")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_401_5") }
        }
    }

    @Test
    fun `탈퇴한 사용자의 토큰은 404를 응답한다`() {
        val user = signUp("kakao-me-3")
        val accessToken = authTokenProvider.generateAccessToken(user)
        userRepository.delete(user)

        // 토큰 서명은 유효하다. 인증(401)이 아니라 대상이 없는 것(404)이므로 구분해서 응답한다.
        mockMvc.get("/api/v1/users/me") {
            header("Authorization", "Bearer ${accessToken.value}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("USER_404_1") }
        }
    }
}
