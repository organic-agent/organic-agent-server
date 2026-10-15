package com.soma.wes.global.cache

import java.time.Duration

/**
 * 서버 여러 대가 함께 보는 캐시(#313 ADR-0002) — 서버 메모리 캐시 뒤의 둘째 층. 담긴 값은 전부 다시 계산할 수 있는 것이라, 이 층이 안 되면
 * 빈 결과로 답하고 예외를 던지지 않는다(fail-open). 호출자는 실패를 다루지 않는다.
 */
interface SharedCache {
    /** [keys] 중 담겨 있는 값. 없는 키는 빠진다. 공유 캐시가 안 되면 빈 맵. */
    fun getAll(keys: List<String>): Map<String, String>

    /** [ttl] 동안 담아 달라고 맡긴다 — 기다리지 않는다. 안 되면 버린다. */
    fun putLater(key: String, value: String, ttl: Duration)
}
