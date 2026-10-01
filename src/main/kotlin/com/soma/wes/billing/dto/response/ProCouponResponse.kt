package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.GalleryPlan
import com.soma.wes.billing.domain.ProCoupon
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class ProCouponResponse(
    @field:Schema(description = "새 프로 갤러리를 개설할 때 보낼 쿠폰 id", example = "1")
    val couponId: Long,
    @field:Schema(description = "쿠폰이 제공하는 요금제", example = "pro")
    val planId: String,
    @field:Schema(description = "미사용이면 AVAILABLE, 비활성화되면 DISABLED, 갤러리에 사용했으면 USED", example = "AVAILABLE")
    val status: String,
    @field:Schema(description = "코드를 등록한 시각. 이 시각부터 이용 기간을 계산하지 않는다.")
    val registeredAt: ZonedDateTime,
    @field:Schema(description = "갤러리 생성에 사용한 시각. 미사용이면 null")
    val consumedAt: ZonedDateTime?,
    @field:Schema(description = "사용한 갤러리 id. 미사용 또는 영구 삭제된 갤러리이면 null")
    val galleryId: Long?,
    @field:Schema(description = "사용일부터 180일 후의 만료 시각. 미사용이면 null")
    val expiresAt: ZonedDateTime?,
) {
    companion object {
        fun from(coupon: ProCoupon): ProCouponResponse = ProCouponResponse(
            couponId = coupon.requiredId,
            planId = GalleryPlan.PRO.planId,
            status = when {
                coupon.consumedAt != null -> "USED"
                coupon.disabledAt != null -> "DISABLED"
                else -> "AVAILABLE"
            },
            registeredAt = checkNotNull(coupon.registeredAt) { "등록한 쿠폰에 등록 시각이 없다." },
            consumedAt = coupon.consumedAt,
            galleryId = coupon.galleryId,
            expiresAt = coupon.expiresAt,
        )
    }
}
