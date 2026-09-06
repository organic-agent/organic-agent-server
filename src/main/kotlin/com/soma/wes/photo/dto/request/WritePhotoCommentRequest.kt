package com.soma.wes.photo.dto.request

import com.soma.wes.photo.domain.PhotoComment
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "부부 내부 사진 댓글 작성 요청")
data class WritePhotoCommentRequest(
    @field:NotBlank
    @field:Size(max = PhotoComment.MAX_CONTENT_LENGTH)
    @field:Schema(description = "1~500자. 앞뒤 공백은 제거되며 작성자는 로그인 계정으로 결정한다.")
    val content: String,
)
