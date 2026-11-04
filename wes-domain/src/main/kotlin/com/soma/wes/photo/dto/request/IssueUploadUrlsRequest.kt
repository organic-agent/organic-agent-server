package com.soma.wes.photo.dto.request

import com.soma.wes.photo.domain.Photo
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

@Schema(description = "업로드 URL 일괄 발급 요청. 파일 하나당 사진 행 하나가 PENDING으로 생긴다. 리사이즈를 끝낸 배치 단위로 부른다.")
data class IssueUploadUrlsRequest(

    @field:NotEmpty
    @field:Valid
    @field:Schema(
        description = "올릴 파일 목록. 한 번에 최대 500개(`app.storage.max-batch-size`)이고, 갤러리 사진 한도를 넘기면 전체를 거절한다. 사진은 보낸 순서대로 기존 사진 뒤에 붙는다.",
    )
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

        @field:NotBlank
        @field:Pattern(regexp = Photo.CRC32C_BASE64_PATTERN)
        @field:Schema(
            description = "PUT 할 바이트의 CRC32C를 base64로 적은 값. x-amz-checksum-crc32c 헤더로 서명에 들어가므로 PUT 할 때 같은 헤더에 같은 값을 보내야 하고, S3가 받은 바이트와 대조해 다르면 거절한다.",
            example = "wdRDgw==",
        )
        val crc32c: String,
    )
}
