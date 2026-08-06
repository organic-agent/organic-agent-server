package com.soma.wes.folder.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "폴더에 사진을 더 담는 요청")
data class AddPhotosRequest(

    @field:NotEmpty
    @field:Schema(description = "담을 사진 id. 이미 들어 있는 사진은 조용히 건너뛴다")
    val photoIds: List<Long>,
)
