package com.soma.wes.photo.infrastructure

import com.soma.wes.global.cache.SharedCache
import com.soma.wes.photo.config.ViewUrlCacheProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ViewUrlCacheTest {

    private val presigner = OfflinePresigner.create()
    private val clock = MovableClock(Instant.now())
    private var signCount = 0

    private val shared = MapSharedCache()

    private fun cache(maxEntries: Int = 1_000, sharedCache: SharedCache = shared) = ViewUrlCache(
        properties = ViewUrlCacheProperties(enabled = true, reuseWindow = Duration.ofMinutes(10), maxEntries = maxEntries),
        sharedCache = sharedCache,
        clock = clock,
    )

    private fun sign(key: String): () -> String = {
        signCount++
        val get = GetObjectRequest.builder().bucket("wes-dev-photos-233927217926").key(key).build()
        val request = GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(25)).getObjectRequest(get).build()
        presigner.presignGetObject(request).url().toExternalForm()
    }

    @Test
    fun `나눠 담았다가 다시 조립한 URL은 SDK가 준 원본과 같다`() {
        // given
        val cache = cache()
        val key = "previews/galleries/1234/3f2a9c1e-7b1d-4f7a-9a51-2c0d5e6f7a8b.jpg"
        val original = cache.get(key, sign(key))

        // when
        val reused = cache.get(key, sign(key))

        // then
        assertThat(reused).isEqualTo(original)
        assertThat(signCount).isEqualTo(1)
    }

    @Test
    fun `창이 지나면 새로 서명한다`() {
        // given
        val cache = cache()
        val key = "previews/galleries/1/a.jpg"
        cache.get(key, sign(key))

        // when
        clock.advance(Duration.ofMinutes(10).plusSeconds(1))
        cache.get(key, sign(key))

        // then
        assertThat(signCount).isEqualTo(2)
    }

    @Test
    fun `사진마다 따로 담아 각자의 URL을 돌려준다`() {
        // given
        val cache = cache()
        val first = cache.get("previews/galleries/1/a.jpg", sign("previews/galleries/1/a.jpg"))
        val second = cache.get("previews/galleries/1/b.jpg", sign("previews/galleries/1/b.jpg"))

        // when
        val firstAgain = cache.get("previews/galleries/1/a.jpg", sign("previews/galleries/1/a.jpg"))
        val secondAgain = cache.get("previews/galleries/1/b.jpg", sign("previews/galleries/1/b.jpg"))

        // then
        assertThat(firstAgain).isEqualTo(first)
        assertThat(secondAgain).isEqualTo(second)
        assertThat(first).isNotEqualTo(second)
        assertThat(signCount).isEqualTo(2)
    }

    @Test
    fun `예상한 모양이 아닌 URL은 담지 않고 원본을 그대로 준다`() {
        // given
        val cache = cache()
        val key = "previews/galleries/1/a.jpg"
        val odd: () -> String = {
            signCount++
            "https://example.test/previews/galleries/1/a.jpg?X-Amz-Signature=short"
        }

        // when
        val first = cache.get(key, odd)
        cache.get(key, odd)

        // then
        assertThat(first).isEqualTo("https://example.test/previews/galleries/1/a.jpg?X-Amz-Signature=short")
        assertThat(signCount).isEqualTo(2)
    }

    @Test
    fun `경로 인코딩이 필요한 키는 담지 않는다`() {
        // given
        val cache = cache()
        val key = "previews/galleries/1/웨딩 사진.jpg"

        // when
        val first = cache.get(key, sign(key))
        cache.get(key, sign(key))

        // then
        assertThat(first).contains("X-Amz-Signature=")
        assertThat(signCount).isEqualTo(2)
    }

    @Test
    fun `상한에 닿으면 비우고 새 사진부터 다시 담는다`() {
        // given
        val cache = cache(maxEntries = 2)
        for (name in listOf("a", "b", "c")) cache.get("previews/galleries/1/$name.jpg", sign("previews/galleries/1/$name.jpg"))

        // when
        cache.get("previews/galleries/1/a.jpg", sign("previews/galleries/1/a.jpg"))
        cache.get("previews/galleries/1/c.jpg", sign("previews/galleries/1/c.jpg"))

        // then
        assertThat(signCount).isEqualTo(4)
    }

    @Test
    fun `새로 서명한 URL은 남은 창만큼 공유 캐시에도 맡긴다`() {
        // given
        val cache = cache()
        val key = "previews/galleries/1/a.jpg"

        // when
        val url = cache.get(key, sign(key))

        // then — 서명 시각(초 단위로 잘림)부터 창 10분, 지금 막 서명했으니 10분 가까이 남는다
        val (stored, ttl) = shared.puts.getValue("wes:view-url:$key")
        assertThat(stored).isEqualTo(url)
        assertThat(ttl).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10))
    }

    @Test
    fun `다른 서버가 서명해 둔 URL은 목록 단위로 한 번 읽어 다시 서명하지 않는다`() {
        // given — 다른 서버가 두 장을 서명해 공유 캐시에 맡겼다
        val other = cache()
        val keys = listOf("previews/galleries/1/a.jpg", "previews/galleries/1/b.jpg")
        val urls = keys.map { other.get(it, sign(it)) }
        val fresh = cache()
        signCount = 0

        // when
        fresh.prefetch(keys)
        val served = keys.map { fresh.get(it, sign(it)) }

        // then
        assertThat(served).isEqualTo(urls)
        assertThat(signCount).isZero()
        assertThat(shared.getAllCalls).isEqualTo(1)
    }

    @Test
    fun `메모리에 이미 있는 사진은 공유 캐시에 묻지 않는다`() {
        // given
        val cache = cache()
        val key = "previews/galleries/1/a.jpg"
        cache.get(key, sign(key))

        // when
        cache.prefetch(listOf(key))

        // then
        assertThat(shared.getAllCalls).isZero()
    }

    @Test
    fun `공유 캐시에 남은 URL이라도 창이 지났으면 쓰지 않고 새로 서명한다`() {
        // given — 다른 서버가 서명한 뒤 창이 지났다(공유 캐시의 만료가 늦게 왔다고 가정)
        val key = "previews/galleries/1/a.jpg"
        cache().get(key, sign(key))
        clock.advance(Duration.ofMinutes(10).plusSeconds(1))
        val fresh = cache()
        signCount = 0

        // when
        fresh.prefetch(listOf(key))
        fresh.get(key, sign(key))

        // then
        assertThat(signCount).isEqualTo(1)
    }

    @Test
    fun `공유 캐시가 안 되면 지금처럼 서명한다`() {
        // given
        val cache = cache(sharedCache = object : SharedCache {
            override fun getAll(keys: List<String>): Map<String, String> = emptyMap()
            override fun putLater(key: String, value: String, ttl: Duration) = Unit
        })
        val key = "previews/galleries/1/a.jpg"

        // when
        cache.prefetch(listOf(key))
        val first = cache.get(key, sign(key))
        val again = cache.get(key, sign(key))

        // then
        assertThat(again).isEqualTo(first)
        assertThat(signCount).isEqualTo(1)
    }

    /** 맡긴 값을 바로 담는 공유 캐시 — 여러 서버가 함께 보는 Redis 자리. */
    private class MapSharedCache : SharedCache {
        val puts = mutableMapOf<String, Pair<String, Duration>>()
        var getAllCalls = 0

        override fun getAll(keys: List<String>): Map<String, String> {
            getAllCalls++
            return keys.mapNotNull { key -> puts[key]?.let { key to it.first } }.toMap()
        }

        override fun putLater(key: String, value: String, ttl: Duration) {
            puts[key] = value to ttl
        }
    }

    private class MovableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
