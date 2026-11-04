package com.soma.wes.admin.resource.dto.response

import com.soma.wes.admin.resource.domain.AdminReprocessScope
import com.soma.wes.admin.resource.domain.AdminResourceType
import java.time.ZonedDateTime

data class AdminReprocessResponse(
    val type: AdminResourceType,
    val id: Long,
    val idempotencyKey: String,
    val scope: AdminReprocessScope,
    val targets: Long,
    val accepted: Boolean,
)

/** 갤러리에서 분석이 결정적으로 실패한 사진 목록. [AdminReprocessScope.FAILED_ONLY] 재처리가 되돌릴 대상과 같다. */
data class AdminAnalysisFailuresResponse(
    val galleryId: Long,
    val failed: Int,
    val photos: List<Photo>,
) {
    data class Photo(
        val photoId: Long,
        val originalFileName: String,
        /** `photo_analysis.error` 값 그대로 — `EMBED_ATTEMPTS_EXCEEDED`·`ANALYSIS_STALLED` 또는 AI 실행기가 쓴 사유. */
        val error: String,
        val failedAt: ZonedDateTime,
    )
}
