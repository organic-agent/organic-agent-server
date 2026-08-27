package com.soma.wes.boundary

import com.soma.wes.WesApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PublicRuntimeBoundaryTest {

    @Test
    fun `public runtime excludes every private admin boundary`() {
        assertClassIsPresent(WesApplication::class.java.name)
        assertClassIsPresent("com.soma.wes.photo.controller.PhotoController")
        assertClassIsPresent("com.soma.wes.security.config.SecurityConfig")

        assertClassIsAbsent("com.soma.wes.admin.resource.controller.AdminResourceController")
        assertClassIsAbsent("com.soma.wes.admin.security.AdminSecurityConfig")
        assertClassIsAbsent("com.soma.wes.security.filter.AdminSessionAuthFilter")
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
