package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션에 사진 담기 요청")
data class AddCollabPhotosRequest(

    @field:Schema(description = "담을 사진 id. 하나라도 이미 담겨 있으면 요청 전체가 409로 거절된다.")
    val photoIds: List<Long>,
)
