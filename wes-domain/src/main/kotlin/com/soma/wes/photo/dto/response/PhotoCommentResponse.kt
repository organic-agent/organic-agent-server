package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.PhotoComment
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "부부의 내부 사진 댓글. 하객 댓글에는 포함되지 않는다.")
data class PhotoCommentResponse(
    val commentId: Long,
    val photoId: Long,
    val authorId: Long,

    @field:Schema(description = "작성자의 현재 닉네임. 탈퇴 계정은 '탈퇴한 사용자'로 표시한다.")
    val nickname: String,

    val content: String,
    val createdAt: ZonedDateTime?,

    @field:Schema(description = "현재 로그인 사용자의 댓글이면 true. 본인 댓글만 삭제할 수 있다.")
    val mine: Boolean,
) {
    companion object {
        fun of(comment: PhotoComment, nickname: String, userId: Long) = PhotoCommentResponse(
            commentId = comment.requiredId,
            photoId = comment.photoId,
            authorId = comment.authorId,
            nickname = nickname,
            content = comment.content,
            createdAt = comment.createdAt,
            mine = comment.authorId == userId,
        )
    }
}
