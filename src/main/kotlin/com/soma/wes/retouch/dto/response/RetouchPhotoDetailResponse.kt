package com.soma.wes.retouch.dto.response

import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.retouch.domain.RetouchPhoto
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "회차 상세의 항목 하나. 원본·주석·결과를 함께 든다")
data class RetouchPhotoDetailResponse(

    val retouchPhotoId: Long,

    @field:Schema(description = "보정을 요청한 원본 사진")
    val photo: PhotoResponse,

    @field:Schema(description = "사진별 보정 요청 텍스트. 적지 않았으면 null이다")
    val requestText: String?,

    @field:Schema(description = "주석 이미지의 서명된 조회 URL. 주석을 그리지 않았으면 null이다")
    val annotationUrl: String?,

    @field:Schema(description = "보정 결과의 서명된 조회 URL. 작가의 응답이 아직 없으면 null이다")
    val resultUrl: String?,
) {

    companion object {
        fun of(
            item: RetouchPhoto,
            photo: PhotoResponse,
            annotationUrl: String?,
            resultUrl: String?,
        ) = RetouchPhotoDetailResponse(
            retouchPhotoId = item.requiredId,
            photo = photo,
            requestText = item.requestText,
            annotationUrl = annotationUrl,
            resultUrl = resultUrl,
        )
    }
}
