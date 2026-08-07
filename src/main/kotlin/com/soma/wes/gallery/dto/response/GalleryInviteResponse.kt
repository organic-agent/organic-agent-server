package com.soma.wes.gallery.dto.response

import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryInviteStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리 초대 링크")
data class GalleryInviteResponse(
    val id: Long,
    val galleryId: Long,

    @field:Schema(description = "예비 부부에게 그대로 전달하는 링크. 토큰이 아니라 완성된 URL이다.")
    val inviteUrl: String,

    @field:Schema(description = "지금 쓸 수 있는지. 저장된 값이 아니라 조회 시점에 계산한다.")
    val status: GalleryInviteStatus,

    val expiresAt: ZonedDateTime,

    @field:Schema(description = "작가가 거둬들인 시각. 폐기하지 않았으면 null이다.")
    val revokedAt: ZonedDateTime?,

    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun of(invite: GalleryInvite, inviteUrl: String, at: ZonedDateTime) = GalleryInviteResponse(
            id = checkNotNull(invite.id) { "저장되지 않은 초대는 응답할 수 없습니다." },
            galleryId = invite.galleryId,
            inviteUrl = inviteUrl,
            status = invite.statusAt(at),
            expiresAt = invite.expiresAt,
            revokedAt = invite.revokedAt,
            createdAt = invite.createdAt,
        )
    }
}
