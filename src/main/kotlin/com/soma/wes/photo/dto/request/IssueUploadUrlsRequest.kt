package com.soma.wes.photo.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

@Schema(description = "업로드 URL 일괄 발급 요청. 파일 하나당 사진 행 하나가 PENDING으로 생긴다. 리사이즈를 끝낸 배치 단위로 부른다.")
data class IssueUploadUrlsRequest(

    @field:NotEmpty
    @field:Valid
    val files: List<FileRequest>,
) {

    @Schema(description = "올리려는 파일 하나")
    data class FileRequest(

        @field:NotBlank
        @field:Size(max = 255)
        @field:Schema(description = "원본 파일명", example = "DSC_0001.JPG")
        val fileName: String,

        @field:NotBlank
        @field:Size(max = 100)
        @field:Schema(
            description = "S3에 PUT 할 때 보낼 Content-Type. 서명에 포함되므로 그대로 보내야 한다.",
            example = "image/jpeg",
        )
        val contentType: String,

        @field:Positive
        @field:Schema(
            description = "실제로 PUT 할 바이트 수. 리사이즈를 끝낸 뒤 발급을 요청해야 알 수 있고, 서명에 포함되므로 다른 크기를 올리면 S3가 거절한다.",
            example = "1843200",
        )
        val contentLength: Long,
    )
}
