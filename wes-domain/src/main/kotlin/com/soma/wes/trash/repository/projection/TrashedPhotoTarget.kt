package com.soma.wes.trash.repository.projection

import java.time.Instant

/**
 * 물리 삭제 대상 사진 하나. 지울 S3 키의 재료와, 즉시 삭제를 막을 수 있는
 * 업로드 URL 만료 시각을 담는다.
 */
data class TrashedPhotoTarget(
    val photoId: Long,
    val storageKey: String,
    val previewKey: String?,
    val uploadUrlExpiresAt: Instant?,
)
