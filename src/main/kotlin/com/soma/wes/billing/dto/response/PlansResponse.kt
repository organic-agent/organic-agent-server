package com.soma.wes.billing.dto.response

import com.soma.wes.billing.config.BillingProperties

data class PlansResponse(
    val mode: String,
    val testCheckoutEnabled: Boolean,
    val plans: List<BillingProperties.Plan>,
)
