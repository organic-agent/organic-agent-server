package com.soma.wes.collab.dto.response

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 하객이 링크를 열었을 때 처음 보는 것.
 *
 * 담기는 값이 적은 것이 의도다. 링크를 주운 사람에게 갤러리 id, 스튜디오, 계약 장수, 마감 기한
 * 같은 안쪽 사정을 알려줄 이유가 없다 — 화면을 그리는 데 필요한 것만 준다.
 */
@Schema(description = "협업 링크로 연 첫 화면")
data class CollabLandingResponse(

    @field:Schema(description = "화면 제목으로 그대로 쓴다.")
    val galleryTitle: String,

    @field:Schema(description = "세션 컨셉 아래 상세폴더에 현재 배정된 사진 수.")
    val photoCount: Long,

    @field:Schema(
        description = "지금 의견을 남길 수 있는지. false면 부부가 이미 고르기를 끝낸 것이라 " +
            "보기만 된다 — 화면은 댓글창과 반응 버튼을 감추면 된다.",
    )
    val writable: Boolean,
)
