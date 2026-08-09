package com.soma.wes.studio.dto.response

import com.soma.wes.studio.domain.StudioDeletionAudit
import java.time.ZonedDateTime
import java.util.UUID

data class StudioDeletionResponse(
    val auditId: Long,
    val requestId: UUID,
    val studioId: Long,
    val galleryCount: Int,
    val photoCount: Int,
    val objectCount: Int,
    val executedAt: ZonedDateTime,
) {

    companion object {

        fun from(audit: StudioDeletionAudit): StudioDeletionResponse =
            StudioDeletionResponse(
                auditId = audit.requiredId,
                requestId = audit.requestId,
                studioId = audit.studioId,
                galleryCount = audit.galleryCount,
                photoCount = audit.photoCount,
                objectCount = audit.objectCount,
                executedAt = checkNotNull(audit.createdAt) { "감사 기록의 생성 시각이 없습니다." },
            )
    }
}
