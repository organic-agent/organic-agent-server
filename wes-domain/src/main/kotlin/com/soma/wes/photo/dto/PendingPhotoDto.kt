package com.soma.wes.photo.dto

import java.time.ZonedDateTime

/** HeadObject로 확인할 PENDING 사진 하나. 스윕이 저장소 조회와 상태 갱신 사이에서 들고 다니는 값이다. */
data class PendingPhotoDto(
    val photoId: Long,
    val storageKey: String,
    val createdAt: ZonedDateTime,
)
