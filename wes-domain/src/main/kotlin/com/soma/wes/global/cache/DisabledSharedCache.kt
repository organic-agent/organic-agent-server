package com.soma.wes.global.cache

import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** 공유 캐시가 꺼져 있으면(서버 한 대·로컬·테스트) 서버마다 자기 메모리 캐시만 쓴다 — 오늘과 같은 동작. */
@Component
@ConditionalOnProperty(prefix = "app.cache.shared", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class DisabledSharedCache : SharedCache {
    override fun getAll(keys: List<String>): Map<String, String> = emptyMap()

    override fun putLater(key: String, value: String, ttl: Duration) = Unit
}
