package com.soma.wes.retouch.dto.request

import com.soma.wes.retouch.domain.RetouchPoint
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid

/** 메모가 없는 선택 사진도 기본 보정 대상이다. */
data class SubmitRetouchRequestsRequest(
    @field:Schema(description = "사진별 보정 요청. 비우면 선택 앨범의 모든 사진을 메모 없이 회차에 담아 제출한다. 보내면 여기 담은 사진만 회차에 들어가고, 같은 사진을 두 번 보낼 수 없다.")
    @field:Valid val requests: List<RetouchRequestItem> = emptyList(),
) {
    data class RetouchRequestItem(
        @field:Schema(description = "보정을 맡길 사진 id. 선택 앨범에 담긴 사진이어야 한다.", example = "1024")
        val photoId: Long,
        @field:Schema(description = "사진 한 장에 대한 보정 요청. 2000자 이하이고, 생략하면 메모 없이 기본 보정 대상이 된다.", example = "왼쪽 눈가 잡티 제거해주세요")
        val requestText: String? = null,
        @field:Schema(description = "사진 위 지점별 요청. 100개 이하, x·y는 0~1로 정규화한 좌표이고 text는 비울 수 없다(2000자 이하). 생략하면 지점 요청이 없다.")
        val points: List<RetouchPoint> = emptyList(),
    )
}
