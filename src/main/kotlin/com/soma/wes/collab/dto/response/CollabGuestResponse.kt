package com.soma.wes.collab.dto.response

import com.soma.wes.collab.domain.CollabGuest
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "입장한 하객")
data class CollabGuestResponse(

    @field:Schema(
        description = "이후 댓글·반응 요청의 X-Guest-Token 헤더에 그대로 싣는다. " +
            "브라우저에 보관해야 다음에 열었을 때 같은 사람으로 이어진다 — 잃어버리면 다시 입장하면 되고, " +
            "그때는 새 사람이 된다.",
    )
    val guestToken: String,

    val nickname: String,
) {

    companion object {
        fun from(guest: CollabGuest) = CollabGuestResponse(
            guestToken = guest.guestToken,
            nickname = guest.nickname,
        )
    }
}
