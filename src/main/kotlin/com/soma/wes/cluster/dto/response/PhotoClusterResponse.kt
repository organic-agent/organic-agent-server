package com.soma.wes.cluster.dto.response

import com.soma.wes.photo.dto.response.PhotoResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "닮은 사진 한 묶음")
data class PhotoClusterResponse(

    @field:Schema(description = "묶음에 든 사진 수. photos.size와 같다")
    val size: Int,

    @field:Schema(
        description = "묶음에 든 사진. 갤러리에서 정한 노출 순서를 따르므로 첫 장을 대표로 쓰면 된다.",
    )
    val photos: List<PhotoResponse>,
) {

    companion object {
        fun of(photos: List<PhotoResponse>) = PhotoClusterResponse(
            size = photos.size,
            photos = photos,
        )
    }
}
