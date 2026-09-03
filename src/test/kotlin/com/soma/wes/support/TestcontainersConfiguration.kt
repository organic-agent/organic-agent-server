package com.soma.wes.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        // 순정 postgres가 아니라 pgvector 확장이 들어 있는 이미지여야 한다. 기준선 마이그레이션의
        // CREATE EXTENSION vector가 실패하면 컨텍스트 로딩이 통째로 깨진다.
        // 로컬(docker-compose.local.yml)·운영과 메이저 버전을 맞춘다.
        PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"),
        )
}
