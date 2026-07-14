package com.soma.wes

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 실패 응답이 어디서 발생하든 `{code, message}` 한 형태로 나가는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ErrorContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {

    @Test
    fun `로그인 URL의 공백과 특수문자가 인코딩된다`() {
        mockMvc.get("/api/v1/oauth/login-url/google")
            .andExpect {
                status { isOk() }
                // scope 구분자인 공백은 %20으로, redirect_uri의 `:`와 `/`도 인코딩되어야 한다.
                jsonPath("$.loginUrl") { value(org.hamcrest.Matchers.containsString("scope=profile%20email")) }
                jsonPath("$.loginUrl") { value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(" "))) }
            }
    }

    @Test
    fun `provider는 대소문자를 가리지 않고 받는다`() {
        // 문서가 광고하는 표기는 소문자 하나뿐이지만(SwaggerDocsTest), 받아줄 때까지 깐깐할 이유는 없다.
        // 어느 표기로 들어오든 OAuthProvider로 정규화되므로 클라이언트가 쓴 철자는 경계를 넘지 못한다.
        listOf("naver", "NAVER", "Naver").forEach { provider ->
            mockMvc.get("/api/v1/oauth/login-url/$provider")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.loginUrl") { value(org.hamcrest.Matchers.containsString("nid.naver.com")) }
                }
        }
    }

    @Test
    fun `지원하지 않는 provider는 원인을 알 수 있는 에러 코드로 응답한다`() {
        // 경로 변수 변환에 실패한 것이므로 그냥 두면 "요청 값이 올바르지 않습니다"(GLOBAL_400_1)로 뭉개진다.
        // provider가 문제라는 걸 클라이언트가 알아야 다른 소셜 로그인을 안내할 수 있다.
        mockMvc.get("/api/v1/oauth/login-url/facebook")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("AUTH_400_1") }
                jsonPath("$.message") { exists() }
            }
    }

    @Test
    fun `본문에 code가 없으면 표준 에러 형식으로 응답한다`() {
        mockMvc.post("/api/v1/oauth/kakao") {
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("GLOBAL_400_2") }
        }
    }

    @Test
    fun `유효하지 않은 refresh token은 401과 토큰 에러 코드를 응답한다`() {
        mockMvc.post("/api/v1/auth/reissue") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"garbage"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_401_6") }
        }
    }

    @Test
    fun `토큰 없이 보호된 경로에 접근하면 토큰 문제가 아니라 인증 필요로 응답한다`() {
        // 토큰을 낸 적도 없는 요청에 "토큰이 비어 있습니다"라고 답해 원인을 오해하게 만들었다.
        mockMvc.get("/api/v1/anything")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("AUTHZ_401_1") }
            }
    }

    @Test
    fun `provider 리다이렉트 경로는 인증에 막히지 않는다`() {
        // 401이 나면 브라우저가 인가 코드를 들고 문 앞에서 막힌다.
        // 이 경로를 처리하는 핸들러는 없으므로 404가 정상이다(인가 코드는 주소창에 남는다).
        mockMvc.get("/login/oauth2/code/google?code=dummy")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("GLOBAL_404_1") }
            }
    }

    @Test
    fun `망가진 토큰으로 보호된 경로에 접근하면 401을 응답한다`() {
        mockMvc.get("/api/v1/anything") {
            header("Authorization", "Bearer not-a-jwt")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_401_2") }
        }
    }
}
