package com.soma.wes.admin.boundary

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AdminRuntimeBoundaryTest {

    @Test
    fun `admin runtime does not contain public application classes`() {
        assertClassIsAbsent("com.soma.wes.photo.controller.PhotoController")
        assertClassIsAbsent("com.soma.wes.security.config.SecurityConfig")
        assertClassIsAbsent("com.soma.wes.WesApplication")
    }

    private fun assertClassIsAbsent(className: String) {
        assertFailsWith<ClassNotFoundException> {
            Class.forName(className, false, javaClass.classLoader)
        }
    }
}
