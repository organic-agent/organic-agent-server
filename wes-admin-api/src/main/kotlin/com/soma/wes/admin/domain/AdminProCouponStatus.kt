package com.soma.wes.admin.domain

/** 쿠폰 사용 마감과 1회용 쿠폰의 Pro 만료는 조회 시점의 Clock으로 계산한다. */
enum class AdminProCouponStatus {
    AVAILABLE, ISSUED, REGISTERED, USED, EXPIRED, DISABLED,
}
