package com.soma.wes.auth.infrastructure

import com.soma.wes.auth.config.AccessCacheProperties
import com.soma.wes.auth.dto.AccessCacheKeyDto
import com.soma.wes.auth.service.port.AccessCache
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 앱 메모리 캐시. 읽기와 담기 사이를 잠그지 않는다 — 같은 키를 둘이 동시에 읽으면 둘 다 DB를 보고 같은 값을 담을 뿐이다.
 * [evict] 직전에 읽기 시작한 요청이 옛 값을 다시 담을 수 있는데, 그 값도 TTL 뒤에는 사라진다 — 반영 지연의 약속(최대 TTL)은 그대로다.
 */
@Component
@ConditionalOnProperty(prefix = "app.access-cache", name = ["enabled"], havingValue = "true")
class InMemoryAccessCache(
    private val properties: AccessCacheProperties,
    private val clock: Clock,
) : AccessCache {

    private val entries = ConcurrentHashMap<AccessCacheKeyDto, Entry>()

    override fun <T> get(key: AccessCacheKeyDto, load: () -> T): T {
        val now = clock.instant()
        val cached = entries[key]
        if (cached != null && now.isBefore(cached.expiresAt)) {
            @Suppress("UNCHECKED_CAST")
            return cached.value as T
        }

        val value = load()
        if (entries.size >= MAX_ENTRIES) prune(now)
        entries[key] = Entry(value = value, expiresAt = now.plus(properties.ttl))
        return value
    }

    /** 지난 값을 치우고, 그래도 상한이면 전부 비운다 — 다시 읽으면 될 값이라 비워도 틀리지 않는다. */
    private fun prune(now: Instant) {
        entries.values.removeIf { entry -> !now.isBefore(entry.expiresAt) }
        if (entries.size >= MAX_ENTRIES) entries.clear()
    }

    override fun evict(keys: Collection<AccessCacheKeyDto>) {
        for (key in keys) entries.remove(key)
    }

    private class Entry(
        val value: Any?,
        val expiresAt: Instant,
    )

    companion object {
        /** 담는 키 수 상한. 키 하나가 수십 바이트라 이만큼도 수 MB다 — 448MB 힙에서 상한 없는 맵을 두지 않으려는 것. */
        private const val MAX_ENTRIES = 100_000
    }
}
