package com.soma.wes.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar

/**
 * 컨텍스트마다 [SharedPostgres] 컨테이너 안에 빈 데이터베이스를 하나 만들어 붙인다.
 *
 * 컨테이너는 테스트 JVM에 하나뿐이고, 데이터베이스는 컨텍스트마다 따로다. 캐시에 남아 있는 다른 컨텍스트의
 * 주기 작업이 지금 도는 테스트의 데이터를 건드리지 않게 하려는 것이다 — 컨테이너를 컨텍스트마다 띄우던 때의 격리를 그대로 둔다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    fun postgresDatabase(): DynamicPropertyRegistrar {
        val url = SharedPostgres.createDatabase()
        return DynamicPropertyRegistrar { registry ->
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { SharedPostgres.container.username }
            registry.add("spring.datasource.password") { SharedPostgres.container.password }
        }
    }
}
