package com.soma.wes.admin.security

import com.soma.wes.admin.audit.service.AdminMutationFailureAuditService
import com.soma.wes.admin.audit.service.AdminResourceReadAuditService
import com.soma.wes.admin.impersonation.service.AdminImpersonationService
import com.soma.wes.admin.service.AdminSessionService
import com.soma.wes.security.exception.CustomAccessDeniedHandler
import com.soma.wes.security.exception.CustomAuthenticationEntryPoint
import com.soma.wes.security.filter.AdminImpersonationReadOnlyFilter
import com.soma.wes.security.filter.AdminMutationFailureAuditFilter
import com.soma.wes.security.filter.AdminMutationHeaderFilter
import com.soma.wes.security.filter.AdminReadAuditFilter
import com.soma.wes.security.filter.AdminSessionAuthFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * 관리자 endpoint의 단일 security chain.
 *
 * 전용 admin 앱과 1차 전환 릴리스의 공개 호환 브리지가 이 설정을 그대로 공유해,
 * 브리지에서 과거의 느슨한 관리자 보안 규칙이 다시 생기지 않게 한다.
 */
@Configuration
@EnableWebSecurity
class AdminSecurityConfig(
    private val adminSessionService: AdminSessionService,
    private val adminImpersonationService: AdminImpersonationService,
    private val adminMutationFailureAuditService: AdminMutationFailureAuditService,
    private val adminResourceReadAuditService: AdminResourceReadAuditService,
    private val authenticationEntryPoint: CustomAuthenticationEntryPoint,
    private val accessDeniedHandler: CustomAccessDeniedHandler,
) {

    @Bean
    @Order(1)
    fun adminFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher("/internal/admin/**")
            cors { disable() }
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }

            authorizeHttpRequests {
                authorize("/internal/admin/v1/auth/login", permitAll)
                authorize("/internal/admin/v1/auth/session", authenticated)
                authorize("/internal/admin/v1/auth/logout", authenticated)
                authorize("/internal/admin/v1/auth/change-password", authenticated)
                authorize(anyRequest, hasRole("SUPER_ADMIN"))
            }

            exceptionHandling {
                authenticationEntryPoint = this@AdminSecurityConfig.authenticationEntryPoint
                accessDeniedHandler = this@AdminSecurityConfig.accessDeniedHandler
            }

            addFilterBefore<UsernamePasswordAuthenticationFilter>(
                AdminMutationFailureAuditFilter(adminMutationFailureAuditService),
            )
            addFilterAfter<AdminMutationFailureAuditFilter>(
                AdminReadAuditFilter(adminResourceReadAuditService),
            )
            addFilterAfter<AdminReadAuditFilter>(
                AdminSessionAuthFilter(adminSessionService, authenticationEntryPoint),
            )
            addFilterAfter<AdminSessionAuthFilter>(
                AdminMutationHeaderFilter(accessDeniedHandler),
            )
            addFilterAfter<AdminMutationHeaderFilter>(
                AdminImpersonationReadOnlyFilter(adminImpersonationService, accessDeniedHandler),
            )
        }
        return http.build()
    }
}
