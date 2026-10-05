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
        description = "지금 댓글을 남길 수 있는지. false면 선택 마감이 지났거나 갤러리가 보관된 것이다 — " +
            "화면은 댓글창을 감추면 된다. 좋아요는 likable을 본다.",
    )
    val writable: Boolean,

    @field:Schema(
        description = "지금 좋아요를 누를 수 있는지. 선택 마감이 지나도 true이고, 갤러리가 보관되거나 " +
            "이용 기간이 끝나면 false다 — 화면은 좋아요 버튼을 감추면 된다.",
    )
    val likable: Boolean,
    val coverTitle: String? = null,
    val coverAuthor: String? = null,
    val expiresAt: java.time.ZonedDateTime? = null,
    val albums: List<Album> = emptyList(),
) {
    data class Album(
        val name: String,
        val photoCount: Long,
        val collabToken: String,
        val sessionId: Long,
    )
}
