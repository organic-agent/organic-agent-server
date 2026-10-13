package com.soma.wes.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 권한 확인 캐시(#286 (k)). 켜면 요청마다 다시 읽던 계정 상태·작업공간·스튜디오 정지 여부를 [ttl] 동안 앱 메모리에서 읽는다
 * ([com.soma.wes.auth.service.port.AccessCache]).
 *
 * R-3-c에서 권한 확인만으로 요청당 ≈ 4문장(계정 25,716 · 갤러리·작업공간 각 24,680 · 스튜디오 16,389번)이었다. 각각은 싸지만
 * 요청 수만큼 반복되며 DB 연결을 그만큼 더 쥔다. wes-api만 켠다 — 관리자 앱과 테스트는 꺼져 있어 지금처럼 매번 DB를 본다.
 */
@ConfigurationProperties(prefix = "app.access-cache")
data class AccessCacheProperties(
    val enabled: Boolean = false,
    /**
     * 담은 값을 믿는 시간. 관리자 정지(계정·스튜디오)는 관리자 앱에서 일어나 이 서버 메모리를 지울 수 없으므로, 정지가 반영되기까지
     * 걸리는 최대 시간이 곧 이 값이다 — 작성자 결정 30초(2026-10-13).
     */
    val ttl: Duration = Duration.ofSeconds(30),
)
