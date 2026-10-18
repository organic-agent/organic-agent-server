package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.ProCoupon
import io.swagger.v3.oas.annotations.media.Schema

data class ProCouponLinkResponse(
    @field:Schema(description = "사용 가능하면 AVAILABLE, 비활성이면 DISABLED, 계정별 사용이 끝났으면 USED, 사용 마감일이 지났으면 EXPIRED.", example = "AVAILABLE")
    val status: Status,
    @field:Schema(description = "사용한 갤러리 중 접근 권한이 있는 갤러리 id. 미사용·권한 없음·삭제이면 null", example = "1")
    val galleryId: Long?,
) {
    enum class Status { AVAILABLE, DISABLED, USED, EXPIRED }

    companion object {
        fun of(status: String, galleryId: Long?): ProCouponLinkResponse = ProCouponLinkResponse(
            status = Status.valueOf(status), galleryId = galleryId,
        )
    }
}
