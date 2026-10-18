package com.soma.wes.billing.domain

/** SINGLE_USE는 기존 선물 코드, 나머지는 여러 계정이 공유하는 코드다. */
enum class CouponUsagePolicy { SINGLE_USE, ONCE_PER_ACCOUNT, UNLIMITED }
