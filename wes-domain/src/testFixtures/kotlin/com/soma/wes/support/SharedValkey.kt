package com.soma.wes.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 JVM 전체가 나눠 쓰는 Valkey 컨테이너 하나(#313, 운영은 ElastiCache Valkey). 리더 임대처럼 Redis 를 직접 다루는 테스트만 쓴다 —
 * 기본 통합 테스트는 리더 임대가 꺼져 있어 Redis 에 붙지 않는다. 끄는 일은 Testcontainers 의 Ryuk 가 JVM 종료 때 맡는다.
 */
object SharedValkey {

    const val PORT = 6379

    val container: GenericContainer<*> =
        // 로컬(docker-compose.local.yml)·운영과 메이저 버전을 맞춘다.
        GenericContainer(DockerImageName.parse("valkey/valkey:8.0-alpine")).apply {
            withExposedPorts(PORT)
            start()
        }

    val host: String
        get() = container.host

    val mappedPort: Int
        get() = container.getMappedPort(PORT)
}
