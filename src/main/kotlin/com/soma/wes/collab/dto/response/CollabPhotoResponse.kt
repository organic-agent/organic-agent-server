package com.soma.wes.collab.dto.response

import com.soma.wes.collab.domain.CollabReaction
import com.soma.wes.photo.dto.response.PhotoResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션에 담긴 사진 한 장")
data class CollabPhotoResponse(

    @field:Schema(description = "댓글·반응은 이 id로 남긴다. 사진 id가 아니다 — 같은 사진이 다른 세션에 담길 수 있다.")
    val collabPhotoId: Long,

    @field:Schema(description = "사진 자체. 하객 화면에도 같은 형태로 나가며 별점(score)은 늘 비어 있다.")
    val photo: PhotoResponse,

    val reactions: CollabReactionCountResponse,

    val commentCount: Long,

    @field:Schema(
        description = "이 요청을 보낸 하객이 누른 반응. 아직 안 눌렀으면 null이고, " +
            "부부·작가가 결과를 볼 때도 null이다 — 그들은 하객이 아니다.",
    )
    val myReaction: CollabReaction?,
)
