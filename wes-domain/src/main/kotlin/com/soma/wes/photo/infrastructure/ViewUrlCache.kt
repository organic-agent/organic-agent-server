package com.soma.wes.photo.infrastructure

import com.soma.wes.global.cache.SharedCache
import com.soma.wes.photo.config.ViewUrlCacheProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 미리보기 서명 URL을 앱 메모리에 담아 [ViewUrlCacheProperties.reuseWindow] 동안 다시 내준다(#286 (p), ADR 0004).
 *
 * URL을 통째로 담지 않는다. 1,500자 남짓 중 사진마다 다른 것은 서명 시각(`X-Amz-Date`)과 서명(`X-Amz-Signature`)뿐이고,
 * 나머지(호스트·보안 토큰·자격 증명 범위)는 같은 자격 증명으로 같은 날 서명한 URL이 모두 같다. 그래서 공통 부분은 [Template]
 * 하나로 나눠 쓰고, 사진마다 서명 시각과 서명 32바이트만 둔다 — 통째면 32MB에 1.9만 장, 이렇게 하면 ≈ 12만 장이다.
 *
 * 나눈 조각을 다시 조립한 URL이 SDK가 준 원본과 글자 하나라도 다르면 담지 않고 원본만 내준다. SDK가 URL 모양을 바꿔도 틀린 주소를
 * 내주는 대신 지금처럼 매번 서명하는 쪽으로 물러난다. S3 URL 형식에 묶인 일이라 `service/port`가 아니라 어댑터 옆에 둔다 —
 * Redis로 옮기는 날 이 클래스 경계에서 인터페이스를 뽑는다.
 *
 * 읽기와 담기 사이를 잠그지 않는다 — 같은 사진을 둘이 동시에 놓치면 둘 다 서명하고 나중 것이 남을 뿐이다.
 *
 * 서버가 여러 대면 이 메모리 뒤에 [SharedCache](Redis)를 둔다(#313 ADR-0002). 새로 뜬 서버는 메모리가 비어 목록마다 200장씩 다시 서명하는데,
 * 재시작 직후 몰린 요청이 그 일로 10초 동안 멈췄다(A-0b-2 로그). 공유 층은 목록 단위로 **한 번에** 읽는다([prefetch]) — 서명 하나(≈ 0.1ms)가
 * Redis 왕복 하나(≈ 0.5ms)보다 싸서, 사진마다 묻는 것은 손해다. 공유 층에는 URL을 통째로 둔다 — 서버마다 자격 증명이 달라 [Template]을
 * 나눠 쓸 수 없다. 받은 서버는 자기 메모리에 나눠 담는다.
 */
@Component
class ViewUrlCache(
    private val properties: ViewUrlCacheProperties,
    private val sharedCache: SharedCache,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val templates = ConcurrentHashMap<Template, Template>()

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val skipped = AtomicLong()
    private val sharedHits = AtomicLong()
    private val lastReportAt = AtomicLong()

    companion object {
        private const val DATE_PARAM = "X-Amz-Date"
        private const val SIGNATURE_PARAM = "X-Amz-Signature"

        /** 공유 캐시 키 앞머리. 같은 Redis 를 리더 임대 등과 나눠 쓴다. */
        private const val SHARED_KEY_PREFIX = "wes:view-url:"

        /** SigV4 서명은 SHA-256 HMAC — 16진수 64자, 32바이트. */
        private const val SIGNATURE_HEX_LENGTH = 64

        /** 공통 부분 상한. 자격 증명이 바뀌거나 날이 바뀔 때만 새로 생겨 하루 몇십 개 — 쌓이지만 않게 막는다. */
        private const val MAX_TEMPLATES = 64

        /** 적중률 로그 간격. */
        private val REPORT_INTERVAL: Duration = Duration.ofMinutes(1)

        /**
         * S3가 경로를 인코딩하지 않는 글자만 쓴 키. 우리 키(`galleries/{id}/{uuid}.{ext}`, `previews/…`)는 모두 여기에 든다.
         * 그래야 조립할 때 키를 그대로 경로에 붙일 수 있다. 다른 글자가 섞인 키는 담지 않는다.
         */
        private val UNENCODED_KEY = Regex("[A-Za-z0-9/._~-]+")

        private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val HEX: HexFormat = HexFormat.of()
    }

    val enabled: Boolean
        get() = properties.enabled

    val reuseWindow: Duration
        get() = properties.reuseWindow

    /**
     * [key]의 미리보기 URL. 서명한 지 [reuseWindow]가 안 지났으면 담아 둔 조각으로 다시 조립하고, 아니면 [sign]으로 새로 서명해
     * 담는다. [sign]은 수명을 `viewUrlTtl + reuseWindow`로 서명해야 한다 — 창 끝에 받은 사람도 `viewUrlTtl`만큼 쓸 수 있게.
     */
    fun get(key: String, sign: () -> String): String {
        val now = clock.instant()
        reportIfDue(now)

        val cached = entries[key]
        if (cached != null && cached.isReusable(now, reuseWindow)) {
            hits.incrementAndGet()
            return cached.toUrl(key)
        }

        misses.incrementAndGet()
        val url = sign()
        val stored = store(key, url, now) ?: return url
        sharedCache.putLater(SHARED_KEY_PREFIX + key, url, Duration.between(now, stored.reusableUntil(reuseWindow)))
        return url
    }

    /**
     * 곧 [get]으로 부를 [keys] 중 메모리에 없는 것을 공유 캐시에서 한 번에 읽어 메모리에 담는다. 목록 한 페이지(200장)를 그리기 전에 부른다.
     * 공유 캐시가 꺼져 있거나 안 되면 아무것도 담지 않는다 — 그러면 [get]이 지금처럼 서명한다.
     */
    fun prefetch(keys: Collection<String>) {
        val now = clock.instant()
        val wanted = keys.distinct().filterNot { key -> entries[key]?.isReusable(now, reuseWindow) == true }
        if (wanted.isEmpty()) return

        val found = sharedCache.getAll(wanted.map { SHARED_KEY_PREFIX + it })
        for (key in wanted) {
            val url = found[SHARED_KEY_PREFIX + key] ?: continue
            if (store(key, url, now) != null) sharedHits.incrementAndGet()
        }
    }

    /** 나눠 담는다. 담지 못했거나(모양이 다름) 이미 창이 지난 URL이면 null. */
    private fun store(key: String, url: String, now: Instant): Entry? {
        val entry = split(key, url)
        if (entry == null || entry.toUrl(key) != url) {
            skipped.incrementAndGet()
            return null
        }
        if (!entry.isReusable(now, reuseWindow)) return null

        if (entries.size >= properties.maxEntries) prune(now)
        entries[key] = entry
        return entry
    }

    /** URL을 공통 부분과 사진마다 다른 조각으로 나눈다. 예상한 모양이 아니면 null — 담지 않는다. */
    private fun split(key: String, url: String): Entry? {
        if (!UNENCODED_KEY.matches(key)) return null

        val path = "/$key?"
        val pathAt = url.indexOf(path)
        if (pathAt < 0) return null
        val host = url.substring(0, pathAt)
        val query = url.substring(pathAt + path.length)

        val date = valueRange(query, DATE_PARAM) ?: return null
        val signature = valueRange(query, SIGNATURE_PARAM) ?: return null
        if (date.last >= signature.first) return null

        val signedAt = parseSignedAt(query.substring(date)) ?: return null
        val signatureBytes = parseSignature(query.substring(signature)) ?: return null

        val template = intern(
            Template(
                host = host,
                beforeDate = query.substring(0, date.first),
                beforeSignature = query.substring(date.last + 1, signature.first),
                afterSignature = query.substring(signature.last + 1),
            ),
        )
        return Entry(template = template, signedAtEpochSecond = signedAt.epochSecond, signature = signatureBytes)
    }

    /** 쿼리에서 `name=` 뒤 값이 놓인 자리. 이름이 없거나 값이 비면 null. */
    private fun valueRange(query: String, name: String): IntRange? {
        val marker = "$name="
        val start = if (query.startsWith(marker)) {
            marker.length
        } else {
            val at = query.indexOf("&$marker")
            if (at < 0) return null
            at + 1 + marker.length
        }
        val end = query.indexOf('&', start).takeIf { it >= 0 } ?: query.length
        return if (end > start) start until end else null
    }

    private fun parseSignedAt(text: String): Instant? =
        runCatching { LocalDateTime.parse(text, AMZ_DATE).toInstant(ZoneOffset.UTC) }.getOrNull()

    private fun parseSignature(text: String): ByteArray? =
        if (text.length == SIGNATURE_HEX_LENGTH) runCatching { HEX.parseHex(text) }.getOrNull() else null

    private fun intern(template: Template): Template {
        if (templates.size >= MAX_TEMPLATES) templates.clear()
        return templates.putIfAbsent(template, template) ?: template
    }

    /** 창이 지난 것을 치우고, 그래도 상한이면 전부 비운다 — 다시 서명하면 될 값이라 비워도 틀리지 않는다. */
    private fun prune(now: Instant) {
        entries.values.removeIf { entry -> !entry.isReusable(now, reuseWindow) }
        if (entries.size >= properties.maxEntries) entries.clear()
    }

    private fun reportIfDue(now: Instant) {
        val nowMillis = now.toEpochMilli()
        val last = lastReportAt.get()
        if (nowMillis - last < REPORT_INTERVAL.toMillis() || !lastReportAt.compareAndSet(last, nowMillis)) return

        val hitCount = hits.getAndSet(0)
        val missCount = misses.getAndSet(0)
        val skippedCount = skipped.getAndSet(0)
        val sharedHitCount = sharedHits.getAndSet(0)
        val total = hitCount + missCount
        if (total == 0L) return

        log.info(
            "event=view_url_cache hits={} shared_hits={} misses={} skipped={} hit_rate={} entries={} templates={}",
            hitCount,
            sharedHitCount,
            missCount,
            skippedCount,
            Math.round(hitCount * 1000.0 / total) / 1000.0,
            entries.size,
            templates.size,
        )
    }

    /** 같은 자격 증명·같은 날 서명한 URL이 모두 같은 부분. 내용이 같으면 [intern]이 한 객체로 모은다. */
    private data class Template(
        val host: String,
        val beforeDate: String,
        val beforeSignature: String,
        val afterSignature: String,
    )

    private class Entry(
        val template: Template,
        val signedAtEpochSecond: Long,
        val signature: ByteArray,
    ) {
        fun reusableUntil(window: Duration): Instant = Instant.ofEpochSecond(signedAtEpochSecond).plus(window)

        fun isReusable(now: Instant, window: Duration): Boolean = now.isBefore(reusableUntil(window))

        fun toUrl(key: String): String = buildString {
            append(template.host).append('/').append(key).append('?')
            append(template.beforeDate)
            append(AMZ_DATE.format(LocalDateTime.ofEpochSecond(signedAtEpochSecond, 0, ZoneOffset.UTC)))
            append(template.beforeSignature)
            append(HEX.formatHex(signature))
            append(template.afterSignature)
        }
    }
}
