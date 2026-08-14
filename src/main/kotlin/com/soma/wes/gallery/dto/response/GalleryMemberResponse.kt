package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.user.domain.User
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

/**
 * 갤러리에 들어와 있는 사람. 작가가 내보낼 대상을 고르는 화면이 쓴다.
 *
 * 닉네임과 이메일은 작가가 "누가 들어왔는지"를 알아보기 위한 것이다. 링크가 엉뚱한 곳으로
 * 퍼졌을 때 [memberId]만 보여주면 둘 중 누구를 내보내야 하는지 가릴 수 없다.
 */
@Schema(description = "갤러리 멤버")
data class GalleryMemberResponse(

    @field:Schema(description = "내보낼 때 쓰는 id. 사용자 id가 아니다.")
    val memberId: Long,

    val userId: Long,
    val nickname: String,
    val email: String?,

    @field:Schema(description = "초대를 수락한 시각")
    val joinedAt: ZonedDateTime?,
) {

    companion object {
        fun of(member: GalleryMember, user: User) = GalleryMemberResponse(
            memberId = member.requiredId,
            userId = member.userId,
            nickname = user.nickname,
            email = user.email,
            joinedAt = member.createdAt,
        )
    }
}
