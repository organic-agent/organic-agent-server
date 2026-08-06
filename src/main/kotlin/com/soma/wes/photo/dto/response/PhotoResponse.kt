package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "사진 한 장")
data class PhotoResponse(
    val photoId: Long,
    val storageKey: String,
    val originalFileName: String,
    val contentType: String,
    val status: PhotoStatus,
    val displayOrder: Int,
    val createdAt: ZonedDateTime?,

    @field:Schema(
        description = "서명된 조회 URL. 버킷이 비공개라 이것 없이는 이미지를 띄울 수 없다. " +
            "아직 올라오지 않은(PENDING) 사진은 null이다.",
    )
    val viewUrl: String?,
) {

    companion object {
        fun of(photo: Photo, viewUrl: String?) = PhotoResponse(
            photoId = photo.requiredId,
            storageKey = photo.storageKey,
            originalFileName = photo.originalFileName,
            contentType = photo.contentType,
            status = photo.status,
            displayOrder = photo.displayOrder,
            createdAt = photo.createdAt,
            viewUrl = viewUrl,
        )
    }
}
