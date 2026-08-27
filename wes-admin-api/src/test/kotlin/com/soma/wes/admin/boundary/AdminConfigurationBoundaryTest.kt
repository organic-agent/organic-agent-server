package com.soma.wes.admin.boundary

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdminConfigurationBoundaryTest {

    @Test
    fun `admin runtime reads only the dedicated parameter prefixes`() {
        val configurations = javaClass.classLoader.getResources("application.yml")
            .asSequence()
            .map { it.readText() }
            .toList()
        val configuration = configurations.single { it.contains("name: wes-admin-api") }

        assertThat(configuration).contains(
            "aws-parameterstore:/wes/admin-api/local/",
            "aws-parameterstore:/wes/admin-api/prod/",
        )
        assertThat(configuration).doesNotContain(
            "aws-parameterstore:/wes/local/",
            "aws-parameterstore:/wes/prod/",
        )
        assertThat(configuration).contains(
            "flyway:\n    enabled: false",
            "api-docs:\n    enabled: false",
            "swagger-ui:\n    enabled: false",
        )
    }
}
