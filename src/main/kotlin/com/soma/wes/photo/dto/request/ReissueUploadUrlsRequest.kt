package com.soma.wes.photo.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive

@Schema(description = "끊긴 업로드의 재개. 아직 올라오지 않은(PENDING) 사진에 새 PUT URL을 받는다. 사진 행은 새로 생기지 않는다.")
data class ReissueUploadUrlsRequest(

    @field:NotEmpty
    @field:Valid
    val photos: List<PhotoRequest>,
) {

    @Schema(description = "다시 올릴 사진 하나")
    data class PhotoRequest(

        @field:Schema(description = "처음 발급 때 받은 사진 id", example = "1")
        val photoId: Long,

        @field:Positive
        @field:Schema(description = "실제로 PUT 할 바이트 수. 서명에 포함된다.", example = "1843200")
        val contentLength: Long,
    )
}
