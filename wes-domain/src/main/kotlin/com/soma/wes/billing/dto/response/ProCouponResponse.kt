package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.CouponUsagePolicy
import com.soma.wes.billing.domain.GalleryPlan
import com.soma.wes.billing.domain.ProCoupon
import com.soma.wes.billing.domain.ProCouponRegistration
import com.soma.wes.billing.domain.ProCouponUse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class ProCouponResponse(
    @field:Schema(description = "새 프로 갤러리를 개설할 때 보낼 쿠폰 id") val couponId: Long,
    @field:Schema(description = "쿠폰이 제공하는 요금제", example = "pro") val planId: String,
    @field:Schema(description = "AVAILABLE, USED, DISABLED 또는 쿠폰 사용 마감일이 지난 EXPIRED") val status: String,
    @field:Schema(description = "본인이 코드를 등록한 시각. Pro 이용 기간은 시작하지 않는다.") val registeredAt: ZonedDateTime,
    @field:Schema(description = "본인의 최근 사용 시각. 미사용이면 null") val consumedAt: ZonedDateTime?,
    @field:Schema(description = "본인의 최근 사용 갤러리 id. 미사용 또는 영구 삭제이면 null") val galleryId: Long?,
    @field:Schema(description = "최근 사용 갤러리의 Pro 만료일. 쿠폰 사용 마감일과 별개다.") val expiresAt: ZonedDateTime?,
    @field:Schema(description = "SINGLE_USE, ONCE_PER_ACCOUNT 또는 UNLIMITED") val usagePolicy: CouponUsagePolicy,
    @field:Schema(description = "쿠폰 사용 마감 시각. null이면 마감 없음") val redeemUntil: ZonedDateTime?,
) {
    companion object {
        fun status(coupon: ProCoupon, used: Boolean, at: ZonedDateTime): String = when {
            used && coupon.usagePolicy != CouponUsagePolicy.UNLIMITED -> "USED"
            coupon.disabledAt != null -> "DISABLED"
            coupon.redeemUntil?.isAfter(at) == false -> "EXPIRED"
            else -> "AVAILABLE"
        }

        fun from(
            coupon: ProCoupon,
            at: ZonedDateTime,
            registration: ProCouponRegistration? = null,
            usage: ProCouponUse? = null,
        ): ProCouponResponse = ProCouponResponse(
            couponId = coupon.requiredId,
            planId = GalleryPlan.PRO.planId,
            status = status(coupon = coupon, used = if (coupon.reusable) usage != null else coupon.consumedAt != null, at = at),
            registeredAt = checkNotNull(if (coupon.reusable) registration?.createdAt else coupon.registeredAt) {
                "등록한 쿠폰에 등록 시각이 없다."
            },
            consumedAt = if (coupon.reusable) usage?.consumedAt else coupon.consumedAt,
            galleryId = if (coupon.reusable) usage?.galleryId else coupon.galleryId,
            expiresAt = if (coupon.reusable) usage?.expiresAt else coupon.expiresAt,
            usagePolicy = coupon.usagePolicy,
            redeemUntil = coupon.redeemUntil,
        )
    }
}
