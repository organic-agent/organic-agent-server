package com.soma.wes.admin.domain

/** 기간 만료는 조회 시점의 Clock으로 계산하며, 등록되지 않은 코드는 기간이 시작되지 않는다. */
enum class AdminProCouponStatus {
    ISSUED, REGISTERED, USED, EXPIRED, DISABLED,
}
