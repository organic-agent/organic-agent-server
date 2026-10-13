package com.soma.wes.billing.dto

import com.soma.wes.billing.domain.GalleryPlan
import com.soma.wes.billing.domain.ProCoupon
import com.soma.wes.billing.domain.TestCheckout
import java.time.ZonedDateTime

data class GalleryPassDto(
    val plan: GalleryPlan?,
    val expiresAt: ZonedDateTime,
    val maxPhotoCount: Int,
    val coupon: ProCoupon? = null,
    val checkout: TestCheckout? = null,
)
