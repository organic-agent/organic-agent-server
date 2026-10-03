package com.soma.wes.photo.dto

import java.time.ZonedDateTime

/** 분석이 결정적으로 실패한 사진 하나와 그 사유(`photo_analysis.error`). 관리자가 갤러리별로 무엇이 왜 빠졌는지 볼 때 쓴다. */
data class AnalysisFailureDto(
    val photoId: Long,
    val originalFileName: String,
    val error: String,
    val failedAt: ZonedDateTime,
)
