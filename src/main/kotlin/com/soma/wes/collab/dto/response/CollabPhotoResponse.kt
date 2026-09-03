package com.soma.wes.collab.dto.response

import com.soma.wes.photo.dto.response.PhotoResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션에 담긴 사진 한 장")
data class CollabPhotoResponse(

    @field:Schema(description = "댓글·좋아요 대상이 되는 사진 id. 세션의 컨셉 카테고리에 현재 배정된 사진이다.")
    val photoId: Long,

    @field:Schema(description = "사진 자체. 하객 화면에도 같은 형태로 나가며 별점(score)은 늘 비어 있다.")
    val photo: PhotoResponse,

    @field:Schema(description = "지금까지 모인 좋아요 수. 하객 한 사람이 하나씩이라 그대로 사람 수다.")
    val likeCount: Long,

    val commentCount: Long,

    @field:Schema(
        description = "이 요청을 보낸 하객이 좋아요를 눌렀는지. 하객 토큰이 없으면 false이고, " +
            "부부·작가가 결과를 볼 때도 false다 — 그들은 하객이 아니다.",
    )
    val liked: Boolean,
)
