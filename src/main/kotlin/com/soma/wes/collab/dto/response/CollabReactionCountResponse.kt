package com.soma.wes.collab.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사진 한 장에 모인 반응 수")
data class CollabReactionCountResponse(
    val good: Long,
    val soso: Long,
    val bad: Long,
) {

    companion object {
        val NONE = CollabReactionCountResponse(good = 0, soso = 0, bad = 0)
    }
}
