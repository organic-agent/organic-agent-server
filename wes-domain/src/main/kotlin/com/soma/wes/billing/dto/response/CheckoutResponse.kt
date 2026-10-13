package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.TestCheckout
import java.time.ZonedDateTime

data class CheckoutResponse(
    val checkoutId: String,
    val planId: String,
    val status: String,
    val mode: String,
    val amount: Long,
    val currency: String,
    val expiresAt: ZonedDateTime,
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
