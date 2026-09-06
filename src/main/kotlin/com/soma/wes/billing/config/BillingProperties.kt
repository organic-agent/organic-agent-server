package com.soma.wes.billing.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** PG 확정 전 실제 금전 처리 없는 테스트 이용권만 지원한다. 활성 여부는 배포 설정으로 제어한다. */
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
