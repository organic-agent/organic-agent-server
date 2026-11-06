package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.ProCouponStatus
import io.swagger.v3.oas.annotations.media.Schema

data class ProCouponLinkResponse(
    @field:Schema(description = "사용 가능하면 AVAILABLE, 비활성이면 DISABLED, 계정별 사용이 끝났으면 USED, 사용 마감일이 지났으면 EXPIRED.", example = "AVAILABLE")
    val status: ProCouponStatus,
    @field:Schema(description = "사용한 갤러리 중 접근 권한이 있는 갤러리 id. 미사용·권한 없음·삭제이면 null", example = "1")
    val galleryId: Long?,
) {
    companion object {
        fun of(status: ProCouponStatus, galleryId: Long?): ProCouponLinkResponse = ProCouponLinkResponse(
            status = status, galleryId = galleryId,
        )
    }
}
