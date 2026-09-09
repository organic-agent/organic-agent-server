package com.soma.wes.analysis.dto

/**
 * 관리자 사진 교체용 임베더 호출 하나 — 정확히 한 리비전의 한 사진. 임베더 Lambda 페이로드로 그대로 직렬화되며
 * `jobId` 키가 있어야 임베더가 관리자 사진 교체 이벤트로 해석한다([StageCallDto.Embed]는 그 키가 없다).
 */
data class ExactPhotoCallDto(
    val jobId: Long,
    val attemptCount: Int,
    val jobType: String,
    val photoId: Long,
    val galleryId: Long,
    val storageKey: String,
    val revisionId: Long,
)
