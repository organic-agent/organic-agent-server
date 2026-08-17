package com.soma.wes.selection.dto.response

import com.soma.wes.photo.dto.response.PhotoResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "선택 앨범의 항목 하나. 원본으로 담았는지 보정본으로 담았는지를 함께 든다")
data class SelectedPhotoResponse(

    @field:Schema(description = "담은 컷의 원본 사진. 보정본으로 담았어도 원본 정보가 실린다")
    val photo: PhotoResponse,

    @field:Schema(description = "보정본으로 담았으면 그 보정 항목의 id. 원본으로 담았으면 null이다")
    val retouchPhotoId: Long?,

    @field:Schema(
        description = "보정본의 서명된 조회 URL. 원본으로 담은 항목은 null이다 — " +
            "그때는 photo.viewUrl을 그린다.",
    )
    val resultUrl: String?,
)
