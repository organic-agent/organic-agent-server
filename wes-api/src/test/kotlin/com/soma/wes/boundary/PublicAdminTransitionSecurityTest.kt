package com.soma.wes.boundary

import com.soma.wes.admin.security.AdminSecurityConfig
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class PublicAdminTransitionSecurityTest @Autowired constructor(
    private val applicationContext: ApplicationContext,
    private val mockMvc: MockMvc,
) {

    @Test
    fun `public transition bridge uses the shared current admin security chain`() {
        assertThat(applicationContext.getBeansOfType(AdminSecurityConfig::class.java)).hasSize(1)
        assertThat(applicationContext.containsBean("adminFilterChain")).isTrue()

        mockMvc.get("/internal/admin/v1/auth/session")
            .andExpect { status { isUnauthorized() } }

        // 최신 admin chain의 mutation-header 경계를 통과하지 못하면 controller에 닿지 않는다.
        mockMvc.post("/internal/admin/v1/auth/login")
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin-only deny fallback is absent so public health remains reachable`() {
        assertThat(
            applicationContext.containsBeanDefinition("adminPrivateRuntimeSecurityBoundaryConfig"),
        ).isFalse()
        mockMvc.get("/actuator/health")
            .andExpect { status { isOk() } }
    }
}
