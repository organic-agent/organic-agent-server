package com.soma.wes.retouch.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

@Schema(description = "S3 PUT을 마친 보정 결과들을 항목에 기록하는 요청")
data class CompleteResultsRequest(

    @field:NotEmpty
    @field:Valid
    @field:Schema(description = "업로드를 마친 결과 목록. 비울 수 없고 resultKey가 서로 겹치면 거절된다. 하나라도 S3에 올라가 있지 않으면 아무것도 기록하지 않는다.")
    val results: List<ResultRequest>,
) {

    @Schema(description = "업로드를 마친 결과 하나")
    data class ResultRequest(

        @field:Schema(description = "결과를 올린 원본 사진 id")
        val photoId: Long,

        @field:NotBlank
        @field:Size(max = 500)
        @field:Schema(
            description = "결과 파일의 storage key. 발급 API가 돌려준 값을 그대로 보낸다 — 이 회차의 결과 경로가 아닌 key는 400으로 거절된다.",
        )
        val resultKey: String,

        @field:NotBlank
        @field:Size(max = 100)
        @field:Schema(description = "업로드한 파일의 Content-Type", example = "image/jpeg")
        val contentType: String,
    )
}
