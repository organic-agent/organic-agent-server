package com.soma.wes.billing.domain

import java.time.ZonedDateTime

/** 한 계정이 본 쿠폰의 상태. 내 혜택 목록과 쿠폰 링크 조회가 같은 판정을 쓴다. */
enum class ProCouponStatus {
    AVAILABLE,
    DISABLED,
    USED,
    EXPIRED,
    ;

    companion object {
        /** 무제한 쿠폰은 써도 USED가 되지 않는다. 사용 여부([used])는 계정별 사용 기록(공유 코드) 또는 쿠폰 행(선물 코드)이 말한다. */
        fun of(coupon: ProCoupon, used: Boolean, at: ZonedDateTime): ProCouponStatus = when {
            used && coupon.usagePolicy != CouponUsagePolicy.UNLIMITED -> USED
            coupon.disabledAt != null -> DISABLED
            coupon.redeemUntil?.isAfter(at) == false -> EXPIRED
            else -> AVAILABLE
        }
    }
}
