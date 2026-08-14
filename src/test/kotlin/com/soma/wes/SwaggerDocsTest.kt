package com.soma.wes

import com.soma.wes.auth.controller.docs.OAuthControllerDocs
import com.soma.wes.auth.domain.OAuthProvider
import io.swagger.v3.oas.annotations.tags.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * API 문서가 인증 없이 열려 있고, 컨트롤러가 실제로 문서에 잡히는지 확인한다.
 * PublicPaths에서 문서 경로가 빠지면 401이 나므로 여기서 걸린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SwaggerDocsTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {

    @Test
    fun `api-docs는 인증 없이 열리고 컨트롤러가 문서에 잡힌다`() {
        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.paths['/api/v1/oauth/{provider}'].post") { exists() }
                jsonPath("$.paths['/api/v1/auth/reissue'].post") { exists() }
                jsonPath("$.paths['/api/v1/studios/me'].delete") { doesNotExist() }
            }
    }

    @Test
    fun `docs 인터페이스의 설명이 문서에 실린다`() {
        // 컨트롤러가 docs 인터페이스를 구현하지 않거나 override를 빠뜨리면 설명이 통째로 사라진다.
        // 서버는 멀쩡히 돌아가므로 문서가 비었다는 사실을 아무도 눈치채지 못한다. 여기서 잡는다.
        //
        // 태그 이름은 @Tag에서 읽어온다. 확인할 것은 표기법(`[OAuth]` 같은 대괄호는 Swagger UI 가독성용이라
        // 언제든 바뀐다)이 아니라 docs 인터페이스의 태그가 문서까지 실려 오느냐이므로, 리터럴로 적어두면
        // 표기를 손볼 때마다 멀쩡한 문서를 두고 테스트만 깨진다.
        val oauthTag = OAuthControllerDocs::class.java.getAnnotation(Tag::class.java).name

        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.paths['/api/v1/oauth/{provider}'].post.summary") { value("소셜 로그인 / 회원가입") }
                jsonPath("$.paths['/api/v1/oauth/{provider}'].post.tags[0]") { value(oauthTag) }
                jsonPath("$.paths['/api/v1/auth/reissue'].post.summary") { value("토큰 재발급") }
                jsonPath("$.paths['/api/v1/users/me'].get.summary") { value("내 정보 조회") }

                // 실패 응답도 문서에 있어야 클라이언트가 분기할 코드를 알 수 있다.
                jsonPath("$.paths['/api/v1/oauth/{provider}'].post.responses.400.content['application/json'].examples") {
                    exists()
                }
                jsonPath("$.paths['/api/v1/users/me'].get.responses.404") { exists() }
            }
    }

    @Test
    fun `로그인 전에 부르는 API는 문서가 토큰을 요구하지 않는다`() {
        // SwaggerConfig가 모든 API에 bearerAuth를 걸어두므로, 로그인·재발급처럼 토큰이 있을 리 없는
        // API는 @SecurityRequirements로 꺼야 한다. 안 그러면 Swagger UI에서 자물쇠가 걸려 시험해볼 수 없다.
        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.paths['/api/v1/oauth/{provider}'].post.security") { isEmpty() }
                jsonPath("$.paths['/api/v1/oauth/login-url/{provider}'].get.security") { isEmpty() }
                jsonPath("$.paths['/api/v1/auth/reissue'].post.security") { isEmpty() }

                // 반대로 인증이 필요한 API는 전역 설정을 그대로 물려받아야 한다.
                jsonPath("$.paths['/api/v1/users/me'].get.security") { doesNotExist() }
            }
    }

    @Test
    fun `JWT bearer 인증 스킴이 문서에 등록된다`() {
        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
                jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat") { value("JWT") }
            }
    }

    @Test
    fun `문서의 provider 표기가 서버가 받는 표기와 같다`() {
        // 문서가 enum 이름(NAVER)을 광고하면 프론트는 그걸 그대로 보내고 400을 받는다.
        // 경로 파라미터와 응답 본문 모두 실제 계약인 OAuthProvider.key여야 한다.
        // 컨트롤러의 allowableValues는 손으로 적은 목록이라, provider를 추가하고 빠뜨리면 여기서 걸린다.
        val keys = OAuthProvider.entries.map { it.key }

        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.paths['/api/v1/oauth/{provider}'].post.parameters[0].schema.enum") { value(keys) }
                jsonPath("$.components.schemas.UserResponse.properties.provider.enum") { value(keys) }
            }
    }

    @Test
    fun `설정한 서버 주소가 문서에 실린다`() {
        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.servers[0].url") { value("http://localhost:8080") }
            }
    }

    @Test
    fun `swagger-ui는 인증 없이 열린다`() {
        mockMvc.get("/swagger-ui/index.html")
            .andExpect { status { isOk() } }
    }
}
