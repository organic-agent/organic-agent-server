package com.soma.wes.gallery.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Max
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.gallery-lifecycle")
data class GalleryLifecycleProperties(
    val enabled: Boolean = true,
    @field:Min(1)
    @field:Max(1000)
    val batchSize: Int = 100,
    /** 보관 정책이 확정되지 않으면 기한을 임의로 정하지 않는다. 파일 삭제와 무관한 표시 기한이다. */
    @field:Min(1)
    val archivedRetentionDays: Int? = null,
)
