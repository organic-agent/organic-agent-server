package com.soma.wes.photo.service

import java.time.Instant

/** S3 PUT 서명 결과. hard delete가 URL 재사용 가능 시간을 판단할 수 있도록 만료 시각도 보존한다. */
data class PresignedUpload(
    val url: String,
    val expiresAt: Instant,
)
