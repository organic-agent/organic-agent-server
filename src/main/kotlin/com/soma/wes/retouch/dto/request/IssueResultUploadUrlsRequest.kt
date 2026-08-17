package com.soma.wes.retouch.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

@Schema(description = "보정 결과 업로드 URL 일괄 발급 요청. 발급은 아무 행도 만들지 않는다")
data class IssueResultUploadUrlsRequest(

    @field:NotEmpty
    @field:Valid
    val files: List<FileRequest>,
) {

    @Schema(description = "결과를 올릴 항목 하나")
    data class FileRequest(

        @field:Schema(description = "결과를 올릴 원본 사진 id. 이 회차에 담긴 사진이어야 한다")
        val photoId: Long,

        @field:NotBlank
        @field:Size(max = 100)
        @field:Schema(
            description = "S3에 PUT 할 때 보낼 Content-Type. 서명에 포함되므로 그대로 보내야 한다.",
            example = "image/jpeg",
        )
        val contentType: String,
    )
}
