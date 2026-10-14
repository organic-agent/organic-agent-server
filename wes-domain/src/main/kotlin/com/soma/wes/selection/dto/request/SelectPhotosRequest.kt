package com.soma.wes.selection.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid

@Schema(description = "선택 앨범에 사진을 담는 요청. 원본은 photoIds로, 보정본은 retouchPhotos로 담는다")
data class SelectPhotosRequest(

    @field:Schema(
        description = "원본으로 담을 사진 id. 이미 담긴 사진이 하나라도 섞여 있거나 다 담고 나서 계약 장수를 " +
            "넘긴다면, 한 장도 담기지 않고 통째로 거절된다.",
    )
    val photoIds: List<Long> = emptyList(),

    @field:Valid
    @field:Schema(
        description = "보정본으로 담을 항목. 두 목록을 합쳐 한 장도 없으면 400이다. " +
            "중복·정원 규칙은 원본 photoId 기준이라, 같은 컷을 원본과 보정본으로 두 번 담을 수 없다.",
    )
    val retouchPhotos: List<RetouchPhotoRequest> = emptyList(),
) {

    @Schema(description = "보정본으로 담는 항목 하나")
    data class RetouchPhotoRequest(

        @field:Schema(description = "원본 사진 id. 앨범 항목은 항상 원본을 가리킨다")
        val photoId: Long,

        @field:Schema(
            description = "담을 보정 항목의 id(회차 조회 응답의 retouchPhotoId). " +
                "원본이 photoId와 다르거나 아직 결과가 없으면 400으로 거절된다.",
        )
        val retouchPhotoId: Long,
    )
}
