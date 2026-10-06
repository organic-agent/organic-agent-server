package com.soma.wes.photo.dto.request

import com.soma.wes.photo.domain.PhotoMemo
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "사진 메모를 적는 요청. 같은 사진에 다시 보내면 덮어쓴다")
data class WritePhotoMemoRequest(

    @field:NotBlank
    @field:Size(max = PhotoMemo.MAX_CONTENT_LENGTH)
    @field:Schema(description = "메모 본문. 비울 때는 이 요청이 아니라 DELETE를 쓴다", example = "엄마가 좋아하실 컷")
    val content: String,
)
