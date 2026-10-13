package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.ProCoupon
import io.swagger.v3.oas.annotations.media.Schema

data class ProCouponLinkResponse(
    @field:Schema(description = "미사용이면 AVAILABLE, 비활성이면 DISABLED, 사용했으면 USED. 미사용 코드는 등록 화면에 남긴다.", example = "AVAILABLE")
    val status: Status,
    @field:Schema(description = "사용한 갤러리 중 접근 권한이 있는 갤러리 id. 미사용·권한 없음·삭제이면 null", example = "1")
    val galleryId: Long?,
) {
    enum class Status { AVAILABLE, DISABLED, USED }

    companion object {
        fun from(coupon: ProCoupon, galleryId: Long?): ProCouponLinkResponse = ProCouponLinkResponse(
            status = when {
                coupon.consumedAt != null -> Status.USED
                coupon.disabledAt != null -> Status.DISABLED
                else -> Status.AVAILABLE
            },
            galleryId = galleryId,
        )
    }
}
