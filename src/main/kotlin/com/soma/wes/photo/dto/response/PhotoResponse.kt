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
            "아직 올라오지 않은(PENDING) 사진은 null이다. " +
            "previewReady가 true면 파생 JPEG를, false면 원본을 가리킨다.",
    )
    val viewUrl: String?,

    @field:Schema(
        description = "viewUrl이 브라우저가 그릴 수 있는 파생 JPEG를 가리키는지. " +
            "false면 원본을 그대로 내려주는 것이라 형식에 따라(HEIC 등) 그려지지 않을 수 있다. " +
            "파생본은 임베딩이 끝나야 생기므로, 그전까지는 '미리보기 준비 중'으로 안내하면 된다.",
    )
    val previewReady: Boolean,

    @field:Schema(
        description = "이 사진에 매겨진 별점(1~5). 아무도 매기지 않았으면 null이다. " +
            "클라이언트 두 명이 공유하며 작가 응답에는 null이다.",
    )
    val score: Int?,

    @field:Schema(
        description = "이 사진의 메모. 아무도 적지 않았으면 null이다. " +
            "개인 갤러리 소유자와 파트너가 공유하며 작가·게스트 응답에는 null이다.",
    )
    val memo: PhotoMemoResponse? = null,
) {

    companion object {
        fun of(photo: Photo, viewUrl: String?, score: Int?, memo: PhotoMemoResponse? = null) = PhotoResponse(
            photoId = photo.requiredId,
            storageKey = photo.storageKey,
            originalFileName = photo.originalFileName,
            contentType = photo.contentType,
            status = photo.status,
            displayOrder = photo.displayOrder,
            createdAt = photo.createdAt,
            viewUrl = viewUrl,
            previewReady = photo.previewKey != null,
            score = score,
            memo = memo,
        )
    }
}
