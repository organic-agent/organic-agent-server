package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.UploadState
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "지문마다의 상태. 요청에 같은 지문이 여러 번 있어도 결과는 지문당 하나다.")
data class CheckUploadsResponse(
    val results: List<Result>,
) {

    @Schema(description = "원본 하나의 상태")
    data class Result(
        val sourceHash: String,

        @field:Schema(
            description = "NEW 는 올려야 하는 원본, PENDING 은 올리다 만 원본(발급을 다시 부르면 같은 사진으로 이어진다), "
                + "UPLOADED 는 건너뛸 원본, TRASHED 는 같은 원본이 휴지통에 있는 경우다(기본은 건너뛰고, 올리면 새 사진이 된다).",
        )
        val state: UploadState,

        @field:Schema(description = "PENDING·UPLOADED 일 때 그 사진의 id. NEW·TRASHED 면 null.", nullable = true)
        val photoId: Long?,
    )
}
