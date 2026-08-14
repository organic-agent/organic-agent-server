package com.soma.wes.trash.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "사진 즉시 물리 삭제 요청. 복구할 수 없다.")
data class EraseTrashedPhotosRequest(

    @field:NotEmpty
    @field:Schema(description = "완전히 지울 사진 id 목록. 전부 휴지통에 있어야 한다", example = "[1, 2, 3]")
    val photoIds: List<Long>,
)
