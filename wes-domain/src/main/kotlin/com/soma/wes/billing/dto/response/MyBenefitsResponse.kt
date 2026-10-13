package com.soma.wes.billing.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class MyBenefitsResponse(
    @field:Schema(description = "계정당 한 번인 무료 갤러리를 아직 생성하지 않았으면 true", example = "true")
    val freePlanAvailable: Boolean,
    @field:Schema(description = "본인이 등록한 프로 쿠폰. 사용한 쿠폰도 이력으로 포함한다.")
    val coupons: List<ProCouponResponse>,
)
