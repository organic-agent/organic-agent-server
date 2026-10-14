package com.soma.wes.auth.infrastructure

import com.soma.wes.auth.config.AccessCacheProperties
import com.soma.wes.auth.service.port.AccessCache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

/** 스위치(`app.access-cache.enabled`)가 캐시를 켜고 끄는지. 통합 테스트 컨텍스트는 꺼진 쪽이라 켜진 조합을 여기서 본다. */
class AccessCacheWiringTest {

    private val runner = ApplicationContextRunner()
        .withBean(AccessCacheProperties::class.java, { AccessCacheProperties() })
        .withBean(Clock::class.java, { Clock.systemDefaultZone() })
        .withUserConfiguration(InMemoryAccessCache::class.java)

    @Test
    fun `켜면 앱 메모리 캐시가 생긴다`() {
        runner.withPropertyValues("app.access-cache.enabled=true").run { context ->
            assertThat(context).hasSingleBean(AccessCache::class.java)
        }
    }

    @Test
    fun `끄면 캐시가 없어 호출자가 매번 DB를 본다`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(AccessCache::class.java)
        }
    }
}
