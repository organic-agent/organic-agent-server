package com.soma.wes.admin.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class IssueProCouponResponse(
    @field:Schema(description = "발급한 프로 쿠폰 id", example = "1")
    val couponId: Long,
    @field:Schema(description = "한 사람에게 전달할 일회용 코드. 이 응답에서만 원문을 반환한다.")
    val code: String,
)
