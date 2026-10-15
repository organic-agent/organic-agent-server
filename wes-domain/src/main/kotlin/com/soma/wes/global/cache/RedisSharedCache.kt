package com.soma.wes.global.cache

import com.soma.wes.global.config.SharedCacheProperties
import jakarta.annotation.PreDestroy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis(ElastiCache Valkey)에 둔 공유 캐시. 요청 경로에 있으므로 Redis 가 느려지거나 죽어도 요청을 붙잡지 않는 것이 이 클래스의 일이다.
 *
 * - 한 번이라도 실패하면 [SharedCacheProperties.openFor] 동안 부르지 않는다(회로 차단). 그동안 [getAll]은 바로 빈 맵이다. 지나면 다음 호출이
 *   다시 시도한다. 실패 하나가 기다리게 하는 시간의 상한은 `spring.data.redis.timeout`이다.
 * - 담기는 자기 스레드 하나로 미룬다 — 요청은 Redis 왕복을 기다리지 않는다. 줄이 차면 버린다.
 */
@Component
@ConditionalOnProperty(prefix = "app.cache.shared", name = ["enabled"], havingValue = "true")
class RedisSharedCache(
    private val redis: StringRedisTemplate,
    private val properties: SharedCacheProperties,
    private val clock: Clock,
) : SharedCache {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 시각까지 Redis 를 부르지 않는다. */
    @Volatile
    private var openUntil: Instant = Instant.MIN

    private val writer = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(properties.writeQueueCapacity),
        { task -> Thread(task, WRITER_THREAD).apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    companion object {
        private const val WRITER_THREAD = "shared-cache-writer"
    }

    override fun getAll(keys: List<String>): Map<String, String> {
        if (keys.isEmpty() || isOpen()) return emptyMap()
        val values = try {
            redis.opsForValue().multiGet(keys)
        } catch (e: RuntimeException) {
            open(e)
            return emptyMap()
        } ?: return emptyMap()

        return buildMap {
            keys.zip(values).forEach { (key, value) -> if (value != null) put(key, value) }
        }
    }

    override fun putLater(key: String, value: String, ttl: Duration) {
        if (ttl.isZero || ttl.isNegative || isOpen()) return
        writer.execute {
            if (isOpen()) return@execute
            try {
                redis.opsForValue().set(key, value, ttl)
            } catch (e: RuntimeException) {
                open(e)
            }
        }
    }

    @PreDestroy
    fun stopWriter() {
        writer.shutdownNow()
    }

    private fun isOpen(): Boolean = clock.instant().isBefore(openUntil)

    private fun open(e: RuntimeException) {
        val wasOpen = isOpen()
        openUntil = clock.instant().plus(properties.openFor)
        if (!wasOpen) log.warn("event=shared_cache.open seconds={} error={}", properties.openFor.seconds, e.toString())
    }
}
