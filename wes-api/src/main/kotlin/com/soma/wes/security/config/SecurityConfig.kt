package com.soma.wes.security.config

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.security.PublicPaths
import com.soma.wes.security.exception.CustomAccessDeniedHandler
import com.soma.wes.security.exception.CustomAuthenticationEntryPoint
import com.soma.wes.security.filter.JwtAuthFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val authTokenProvider: AuthTokenProvider,
    private val authenticationEntryPoint: CustomAuthenticationEntryPoint,
    private val accessDeniedHandler: CustomAccessDeniedHandler,

    @Value("\${cors.allowed-origins}")
    private val allowedOrigins: List<String>,
) {

    companion object {
        /** 브라우저가 사전 요청(`OPTIONS`) 결과를 다시 쓰는 시간(초). 없으면 API 호출마다 사전 요청이 한 번 더 간다. */
        private const val CORS_PREFLIGHT_MAX_AGE_SECONDS = 3600L
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            cors { configurationSource = corsConfigurationSource() }

            // JWT 기반 무상태 API라 세션·CSRF 토큰·폼 로그인이 모두 불필요하다.
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }

            authorizeHttpRequests {
                PublicPaths.PATTERNS.forEach { authorize(it, permitAll) }
                authorize("/api/v1/admin/**", hasRole("ADMIN"))
                authorize(anyRequest, authenticated)
            }

            exceptionHandling {
                authenticationEntryPoint = this@SecurityConfig.authenticationEntryPoint
                accessDeniedHandler = this@SecurityConfig.accessDeniedHandler
            }

            // 빈으로 두지 않고 여기서 직접 만든다. `@Component`를 붙이면 Spring Boot가 이 필터를
            // 서블릿 컨테이너에도 자동 등록해, 시큐리티 체인 밖에서 한 번 더 호출된다.
            // (docs/jwt-filter-double-registration.md)
            addFilterBefore<UsernamePasswordAuthenticationFilter>(
                JwtAuthFilter(authTokenProvider, authenticationEntryPoint),
            )
        }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfig.allowedOrigins
            addAllowedHeader("*")
            addAllowedMethod("*")
            allowCredentials = true
            maxAge = CORS_PREFLIGHT_MAX_AGE_SECONDS
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}
