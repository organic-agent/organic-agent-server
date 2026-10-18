package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAuthEvent
import com.soma.wes.billing.domain.CouponUsagePolicy
import com.soma.wes.billing.domain.ProCoupon
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.ZonedDateTime

data class IssueProCouponRequest(
    @field:NotBlank @field:Size(max = AdminAuthEvent.REASON_MAX_LENGTH)
    @field:Schema(description = "코드 발급 사유") val reason: String,
    @field:Size(max = ProCoupon.MAX_CODE_LENGTH)
    @field:Schema(description = "직접 지정할 코드. 영문·숫자·하이픈·밑줄, 대소문자 구분 없음. null이면 자동 생성")
    val code: String? = null,
    @field:Schema(description = "1회용 SINGLE_USE, 공유·계정당 1회 ONCE_PER_ACCOUNT, 공유·반복 사용 UNLIMITED")
    val usagePolicy: CouponUsagePolicy = CouponUsagePolicy.SINGLE_USE,
    @field:Schema(description = "쿠폰을 사용할 수 있는 마감 시각. null이면 마감 없음. Pro 갤러리 이용기간과 별개")
    val redeemUntil: ZonedDateTime? = null,
)
