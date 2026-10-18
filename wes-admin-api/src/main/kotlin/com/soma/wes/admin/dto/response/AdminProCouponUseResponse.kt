package com.soma.wes.admin.dto.response

import com.soma.wes.admin.dto.AdminProCouponUseDto
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class AdminProCouponUseResponse(
    @field:Schema(description = "개별 사용 이력 id") val useId: Long,
    @field:Schema(description = "사용 계정 id. 영구 삭제되면 null") val userId: Long?,
    @field:Schema(description = "현재 계정 이름. 삭제되면 null") val userNickname: String?,
    @field:Schema(description = "사용한 갤러리 id. 영구 삭제되면 null") val galleryId: Long?,
    @field:Schema(description = "현재 갤러리 이름. 휴지통·삭제이면 null") val galleryTitle: String?,
    @field:Schema(description = "쿠폰 사용 시각") val consumedAt: ZonedDateTime,
    @field:Schema(description = "해당 갤러리의 Pro 이용 만료일") val expiresAt: ZonedDateTime,
) {
    companion object {
        fun from(row: AdminProCouponUseDto): AdminProCouponUseResponse = AdminProCouponUseResponse(
            useId = row.useId, userId = row.userId, userNickname = row.userNickname, galleryId = row.galleryId,
            galleryTitle = row.galleryTitle, consumedAt = row.consumedAt, expiresAt = row.expiresAt,
        )
    }
}
