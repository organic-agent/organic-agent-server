package com.soma.wes.boundary

import com.soma.wes.WesApplication
import com.soma.wes.transition.PublicAdminTransitionBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PublicRuntimeBoundaryTest {

    @Test
    fun `first cutover release explicitly carries the removable admin bridge`() {
        val marker = WesApplication::class.java.getAnnotation(PublicAdminTransitionBridge::class.java)
        assertNotNull(marker)
        assertEquals(
            "WES_PUBLIC_ADMIN_TRANSITION_BRIDGE_REMOVE_AFTER_BACKOFFICE_CUTOVER",
            PublicAdminTransitionBridge.REMOVAL_MARKER,
        )
        assertClassIsPresent("com.soma.wes.admin.resource.controller.AdminResourceController")
        assertClassIsPresent("com.soma.wes.admin.security.AdminSecurityConfig")
        assertClassIsPresent("com.soma.wes.security.filter.AdminSessionAuthFilter")

        // 별도 앱의 bootstrap/fail-closed fallback은 공개 artifact에 섞지 않는다.
        assertClassIsAbsent("com.soma.wes.WesAdminApiApplication")
        assertClassIsAbsent("com.soma.wes.admin.security.AdminPrivateRuntimeSecurityBoundaryConfig")
    }

    private fun assertClassIsPresent(className: String) {
        Class.forName(className, false, javaClass.classLoader)
    }

    private fun assertClassIsAbsent(className: String) {
        assertFailsWith<ClassNotFoundException> {
            Class.forName(className, false, javaClass.classLoader)
        }
    }
}
