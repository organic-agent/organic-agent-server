package com.soma.wes.photo.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "업로드 완료 통보. S3 업로드는 프론트가 직접 하므로 서버는 끝난 사실을 알 수 없다.",)
data class CompleteUploadRequest(

    @field:NotEmpty
    @field:Schema(description = "S3 PUT을 마친 사진 id 목록", example = "[1, 2, 3]")
    val photoIds: List<Long>,
)
