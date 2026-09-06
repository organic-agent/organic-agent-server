package com.soma.wes.billing.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** PG 확정 전 테스트 결제만 지원한다. 운영 프로필은 설정과 무관하게 결제가 차단된다. */
@ConfigurationProperties("app.billing")
data class BillingProperties(
    val testCheckoutEnabled: Boolean = false,
    val plans: List<Plan> = listOf(Plan()),
) {
    data class Plan(
        val id: String = "test-30-days",
        val name: String = "테스트 플랜",
        val amount: Long = 0,
        val currency: String = "KRW",
        val durationDays: Int = 30,
        val maxPhotoCount: Int = 10000,
    )
}
