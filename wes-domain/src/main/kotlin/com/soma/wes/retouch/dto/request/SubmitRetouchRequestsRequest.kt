package com.soma.wes.retouch.dto.request

import com.soma.wes.retouch.domain.RetouchPoint
import jakarta.validation.Valid

/** 메모가 없는 선택 사진도 기본 보정 대상이다. */
data class SubmitRetouchRequestsRequest(
    @field:Valid val requests: List<RetouchRequestItem> = emptyList(),
)

data class RetouchRequestItem(
    val photoId: Long,
    val requestText: String? = null,
    val points: List<RetouchPoint> = emptyList(),
)
