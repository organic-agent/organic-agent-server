package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.TestCheckout
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class CheckoutResponse(
    val checkoutId: String,
    val planId: String,
    val status: String,
    val mode: String,
    val amount: Long,
    val currency: String,
    val expiresAt: ZonedDateTime,
    @field:Schema(description = "이 이용권을 써서 만든 개인 갤러리 id. 아직 갤러리를 만들지 않았으면 null이다.", example = "42")
    val galleryId: Long?,
) {
    companion object {
        fun from(checkout: TestCheckout) = CheckoutResponse(
            checkoutId = checkout.id, planId = checkout.planId, status = "COMPLETED", mode = "TEST",
            amount = checkout.amount, currency = checkout.currency, expiresAt = checkout.expiresAt,
            galleryId = checkout.galleryId,
        )
    }
}
