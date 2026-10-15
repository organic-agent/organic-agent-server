package com.soma.wes.global.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 서버 여러 대가 함께 쓰는 캐시(Redis) 손잡이(#313 ADR-0002). 꺼져 있으면 서버마다 자기 메모리 캐시만 쓴다 — 오늘과 같다.
 *
 * 공유 캐시는 없어도 되는 층이다: 안 되면 [openFor] 동안 아예 부르지 않고(회로 차단) 서버 메모리·직접 계산으로 답한다. 그래서 요청 하나가
 * Redis 장애를 기다리는 시간은 `spring.data.redis.timeout` 한 번이 상한이고, 그 뒤 [openFor] 동안은 0이다.
 */
@ConfigurationProperties(prefix = "app.cache.shared")
data class SharedCacheProperties(
    val enabled: Boolean = false,
    val openFor: Duration = Duration.ofSeconds(30),
    /** 담기는 기다리지 않고 맡긴다. 이만큼 밀리면 버린다 — 다시 계산하면 되는 값이다. */
    val writeQueueCapacity: Int = 2_000,
)
