package com.soma.wes.trash.repository.projection

import com.soma.wes.photo.domain.PhotoStatus
import java.time.ZonedDateTime

/** 휴지통 사진 한 줄. [TrashedGalleryRow]와 같은 이유로 읽기 전용 행이다. */
data class TrashedPhotoRow(
    val photoId: Long,
    val originalFileName: String,
    val status: PhotoStatus,
    val storageKey: String,
    val previewKey: String?,
    val deletedAt: ZonedDateTime,
)
