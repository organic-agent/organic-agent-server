package com.soma.wes.retouch.dto.response

import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.retouch.domain.RetouchPhoto
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "회차에 담긴 보정 요청 한 건")
data class RetouchPhotoResponse(

    val retouchPhotoId: Long,

    @field:Schema(description = "보정을 요청한 원본 사진")
    val photo: PhotoResponse,

    @field:Schema(description = "사진별 보정 요청 텍스트. 아직 적지 않았으면 null이다")
    val requestText: String?,

    @field:Schema(description = "주석 이미지의 서명된 조회 URL. 주석을 그리지 않았으면 null이다")
    val annotationUrl: String?,

    @field:Schema(description = "작가의 보정 결과가 올라왔는지. 결과 URL은 회차 상세 API가 준다")
    val hasResult: Boolean,
) {

    companion object {
        fun of(item: RetouchPhoto, photo: PhotoResponse, annotationUrl: String?) = RetouchPhotoResponse(
            retouchPhotoId = item.requiredId,
            photo = photo,
            requestText = item.requestText,
            annotationUrl = annotationUrl,
            hasResult = item.hasResult,
        )
    }
}
