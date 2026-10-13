package com.soma.wes.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig(
    /**
     * Swagger UI가 "Try it out"으로 요청을 쏘는 대상. 명시하지 않으면 문서를 내려받은 주소를 그대로 쓴다.
     * 배포 환경이 리버스 프록시 뒤에 있으면 내부 주소가 잡히므로 외부 주소로 덮어쓴다.
     */
    @Value("\${springdoc.server-url}")
    private val serverUrl: String,
) {

    companion object {
        private const val BEARER_SCHEME = "bearerAuth"
    }

    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .servers(listOf(Server().url(serverUrl)))
            .info(
                Info()
                    .title("wes API")
                    .description("웨딩 이지 셀렉 API")
                    .version("v1"),
            )
            // Authorize에 토큰을 한 번 넣으면 모든 요청에 Authorization 헤더가 붙는다.
            // 개별 API에서 인증이 필요 없다면 @SecurityRequirements로 끌 수 있다.
            .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
            .components(
                Components().addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        // Swagger UI가 "Bearer " 접두사를 자동으로 붙여주므로 토큰 값만 입력하면 된다.
                        .bearerFormat("JWT"),
                ),
            )
}
