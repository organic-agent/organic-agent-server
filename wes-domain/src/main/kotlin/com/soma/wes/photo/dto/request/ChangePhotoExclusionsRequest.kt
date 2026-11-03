package com.soma.wes.photo.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

data class ChangePhotoExclusionsRequest(
    @field:NotEmpty
    @field:Schema(description = "제외 또는 복원할 사진 id. 요청당 app.storage.max-batch-size(500) 이하, 중복은 한 장으로 처리", example = "[1, 2]")
    val photoIds: List<Long>,
)
