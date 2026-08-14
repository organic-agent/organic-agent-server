package com.soma.wes.folder.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "사진을 같은 부모의 다른 자식폴더로 옮기는 요청")
data class MovePhotosRequest(

    @field:Schema(description = "옮겨 넣을 자식폴더 id. 출발지와 같은 부모 아래여야 한다")
    val targetFolderId: Long,

    @field:NotEmpty
    @field:Schema(description = "옮길 사진 id. 전부 출발지 폴더에 들어 있어야 한다")
    val photoIds: List<Long>,
)
