package com.soma.wes.photo.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 조회 서명 URL 캐시(#286 (p), ADR 0004). 켜면 같은 사진의 미리보기 URL을 [reuseWindow] 동안 다시 서명하지 않는다
 * ([com.soma.wes.photo.infrastructure.ViewUrlCache]).
 *
 * 2-0(JFR)에서 앱 CPU의 63.7%가 미리보기 서명이었다 — 목록 한 페이지(200장)마다 사진마다 새로 서명해, 같은 사진도 요청마다 URL이
 * 달랐다. wes-api만 켠다 — 관리자 앱과 테스트는 꺼져 있어 지금처럼 매번 [StorageProperties.viewUrlTtl]로 서명한다.
 */
@ConfigurationProperties(prefix = "app.storage.view-url-cache")
data class ViewUrlCacheProperties(
    val enabled: Boolean = false,
    /**
     * 한 번 서명한 URL을 다시 내주는 시간. 켜면 서명 수명이 [StorageProperties.viewUrlTtl] + 이 값이 된다 — 창 끝에 받은 사람도
     * `viewUrlTtl`(15분)만큼은 쓸 수 있어야 하기 때문이다. 웹은 받은 지 12분에 목록을 다시 읽는다(`usePhotoUrlRefresh.ts`).
     * 늘리면 유출된 URL이 사는 시간도 같이 는다(지금 최대 25분).
     */
    val reuseWindow: Duration = Duration.ofMinutes(10),
    /**
     * 담는 사진 수 상한. 항목 하나 ≈ 250~300B(키 문자열 + 서명 시각 + 서명 32B, 공통 쿼리는 나눠 씀)라 ≈ 32MB(작성자 결정)다.
     * 부하 대본 400명(갤러리 200 × 499장 ≈ 10만 장)까지 담는다.
     */
    val maxEntries: Int = 120_000,
)
