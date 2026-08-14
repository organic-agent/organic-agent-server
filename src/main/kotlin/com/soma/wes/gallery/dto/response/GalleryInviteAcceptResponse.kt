package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.GalleryMember
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 수락 결과. 링크에는 토큰만 실려 있어서 프론트는 이 응답을 받기 전까지 어느 갤러리로
 * 보내야 하는지 모른다. [galleryId]가 그 다음 화면을 정한다.
 */
@Schema(description = "초대 수락 결과")
data class GalleryInviteAcceptResponse(
    val galleryId: Long,

    @field:Schema(description = "이 사용자의 갤러리 멤버 id. 이미 들어와 있었다면 그때 만들어진 값이다.")
    val memberId: Long,
) {

    companion object {
        fun from(member: GalleryMember) = GalleryInviteAcceptResponse(
            galleryId = member.galleryId,
            memberId = member.requiredId,
        )
    }
}
