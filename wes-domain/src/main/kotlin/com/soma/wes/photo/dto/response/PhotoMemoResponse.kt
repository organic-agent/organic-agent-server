package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.PhotoMemo
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "사진 메모. 사진당 하나이며 참여자가 함께 고치고 마지막에 쓴 사람이 덮어쓴다")
data class PhotoMemoResponse(
    val photoId: Long,

    @field:Schema(description = "메모 본문", example = "엄마가 좋아하실 컷")
    val content: String,

    @field:Schema(description = "마지막으로 메모를 고친 사용자 id. 개인 갤러리 소유자 또는 파트너다")
    val updatedBy: Long,

    @field:Schema(description = "마지막으로 고친 시각")
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun from(memo: PhotoMemo) = PhotoMemoResponse(
            photoId = memo.photoId,
            content = memo.content,
            updatedBy = memo.updatedBy,
            updatedAt = memo.updatedAt,
        )
    }
}
