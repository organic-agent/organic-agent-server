package com.soma.wes.admin.security

import com.soma.wes.security.exception.CustomAccessDeniedHandler
import com.soma.wes.security.exception.CustomAuthenticationEntryPoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/** 전용 admin artifact에서 health 외 비관리자 경로를 fail-closed로 닫는 런타임 경계. */
@Configuration
class AdminPrivateRuntimeSecurityBoundaryConfig(
    private val authenticationEntryPoint: CustomAuthenticationEntryPoint,
    private val accessDeniedHandler: CustomAccessDeniedHandler,
) {

    @Bean
    @Order(2)
    fun healthFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher("/actuator/health", "/actuator/health/**")
            cors { disable() }
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
        }
        return http.build()
    }

    @Bean
    @Order(3)
    fun denyUnmatchedRequests(http: HttpSecurity): SecurityFilterChain {
        http {
            cors { disable() }
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests { authorize(anyRequest, denyAll) }
            exceptionHandling {
                authenticationEntryPoint = this@AdminPrivateRuntimeSecurityBoundaryConfig.authenticationEntryPoint
                accessDeniedHandler = this@AdminPrivateRuntimeSecurityBoundaryConfig.accessDeniedHandler
            }
        }
        return http.build()
    }
}
