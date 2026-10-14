package com.soma.wes.trash.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 휴지통 정책.
 *
 * 보관 기간은 비밀이 아니라 제품 정책이라 저장소의 yml에 둔다. 업로드 URL TTL(30분)보다
 * 충분히 길어야 한다 — 만료 purge가 활성 업로드 URL을 따로 확인하지 않는 근거가 이 간격이다.
 */
@ConfigurationProperties(prefix = "app.trash")
data class TrashProperties(

    /** 휴지통에 머무는 기간. 지나면 purge가 S3 객체와 함께 물리 삭제한다. */
    val retention: Duration = Duration.ofDays(7),
)
