package com.soma.wes.retouch.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import jakarta.validation.constraints.Min
import java.time.Duration

/** 동기 PDF 생성이 API 서버의 메모리와 요청 처리 시간을 독점하지 않도록 제한한다. */
@Validated
@ConfigurationProperties(prefix = "app.retouch.pdf")
data class RetouchPdfProperties(
    @field:Min(1) val maxPhotos: Int = 200,
    @field:Min(1) val maxPages: Int = 1000,
    @field:Min(1) val maxTextCharacters: Int = 200_000,
    @field:Min(1) val maxOutputBytes: Int = 50 * 1024 * 1024,
    @field:Min(1) val imageLongEdge: Int = 2048,
    @field:Min(1) val maxConcurrentGenerations: Int = 1,
    val generationTimeout: Duration = Duration.ofSeconds(45),
) {
    init {
        require(!generationTimeout.isNegative && !generationTimeout.isZero) { "PDF timeout must be positive" }
    }
}
