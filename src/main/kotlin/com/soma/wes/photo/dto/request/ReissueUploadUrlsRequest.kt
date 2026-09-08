package com.soma.wes.photo.dto.request

import com.soma.wes.photo.domain.Photo
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
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

        @field:NotBlank
        @field:Pattern(regexp = Photo.CRC32C_BASE64_PATTERN)
        @field:Schema(
            description = "PUT 할 바이트의 CRC32C(base64). 처음 발급 때와 같은 파일이면 같은 값이다. x-amz-checksum-crc32c 헤더로 서명에 들어간다.",
            example = "wdRDgw==",
        )
        val crc32c: String,
    )
}
