package com.soma.wes.support

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 테스트 JVM 전체가 나눠 쓰는 PostgreSQL 컨테이너 하나. [TestcontainersConfiguration]이 컨텍스트마다 [createDatabase]로
 * 데이터베이스를 따로 만든다. 컨텍스트마다 컨테이너를 띄우면 컨텍스트 수만큼 컨테이너 기동이 쌓인다(CI 22개).
 * 끄는 일은 Testcontainers의 Ryuk가 JVM 종료 때 맡는다.
 */
object SharedPostgres {

    private const val POSTGRES_PORT = 5432

    // 캐시된 컨텍스트들이 각자 연결 풀(사용자 10 + 분석 최대 7)을 쥔 채 한 컨테이너에 붙는다. 기본 100으로는 모자란다.
    private const val MAX_CONNECTIONS = 500

    private val sequence = AtomicInteger()

    val container: PostgreSQLContainer<*> =
        // 순정 postgres가 아니라 pgvector 확장이 들어 있는 이미지여야 한다. 기준선 마이그레이션의
        // CREATE EXTENSION vector가 실패하면 컨텍스트 로딩이 통째로 깨진다.
        // 로컬(docker-compose.local.yml)·운영과 메이저 버전을 맞춘다.
        PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            // 테스트 데이터는 버려도 되므로 디스크 동기화를 끈다(Testcontainers 기본값 fsync=off에 둘을 더한다).
            .withCommand(
                "postgres",
                "-c", "max_connections=$MAX_CONNECTIONS",
                "-c", "fsync=off",
                "-c", "synchronous_commit=off",
                "-c", "full_page_writes=off",
            )
            .apply { start() }

    /** 새 데이터베이스를 만들고 그 JDBC URL을 돌려준다. 마이그레이션은 붙는 컨텍스트의 Flyway가 돌린다. */
    fun createDatabase(): String {
        val name = "wes_test_${sequence.incrementAndGet()}"
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return "jdbc:postgresql://${container.host}:${container.getMappedPort(POSTGRES_PORT)}/$name"
    }
}
