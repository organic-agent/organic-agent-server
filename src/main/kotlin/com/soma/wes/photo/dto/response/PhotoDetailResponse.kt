package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(
    description = "사진 한 장의 상세. 목록의 항목(PhotoResponse)과 달리 원본 URL과 촬영 정보가 함께 온다.",
)
data class PhotoDetailResponse(
    val photoId: Long,
    val storageKey: String,
    val originalFileName: String,
    val contentType: String,
    val status: PhotoStatus,
    val displayOrder: Int,
    val createdAt: ZonedDateTime?,

    @field:Schema(
        description = "화면에 그릴 URL. 파생 JPEG가 있으면 그쪽을, 없으면 원본을 가리킨다. " +
            "아직 올라오지 않은(PENDING) 사진은 null이다.",
    )
    val viewUrl: String?,

    @field:Schema(
        description = "원본을 원래 크기로 여는 URL. 아직 올라오지 않은(PENDING) 사진은 null이다. " +
            "원본은 형식에 따라(아이폰 HEIC 등) 브라우저가 그리지 못할 수 있으므로, " +
            "먼저 viewUrl로 그리고 확대·다운로드에 이 URL을 쓴다.",
    )
    val originalUrl: String?,

    @field:Schema(
        description = "viewUrl이 브라우저가 그릴 수 있는 파생 JPEG를 가리키는지. " +
            "false면 viewUrl과 originalUrl이 같은 객체를 가리킨다(수명만 다르다).",
    )
    val previewReady: Boolean,

    @field:Schema(description = "viewUrl이 살아 있는 시간(초)")
    val viewUrlTtlSeconds: Long,

    @field:Schema(
        description = "originalUrl이 살아 있는 시간(초). 상세는 오래 열어두는 화면이라 viewUrl보다 길다",
    )
    val originalUrlTtlSeconds: Long,

    @field:Schema(description = "촬영 정보. 아직 채워지지 않았거나 원본에 EXIF가 없으면 null이다")
    val metadata: PhotoMetadataResponse?,

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
        fun of(
            photo: Photo,
            viewUrl: String?,
            originalUrl: String?,
            viewUrlTtlSeconds: Long,
            originalUrlTtlSeconds: Long,
            score: Int?,
            memo: PhotoMemoResponse?,
        ) = PhotoDetailResponse(
            photoId = photo.requiredId,
            storageKey = photo.storageKey,
            originalFileName = photo.originalFileName,
            contentType = photo.contentType,
            status = photo.status,
            displayOrder = photo.displayOrder,
            createdAt = photo.createdAt,
            viewUrl = viewUrl,
            originalUrl = originalUrl,
            previewReady = photo.previewKey != null,
            viewUrlTtlSeconds = viewUrlTtlSeconds,
            originalUrlTtlSeconds = originalUrlTtlSeconds,
            metadata = PhotoMetadataResponse.from(photo.metadata),
            score = score,
            memo = memo,
        )
    }
}
