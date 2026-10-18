package com.soma.wes.admin.dto

import java.time.ZonedDateTime

data class AdminProCouponUseDto(
    val useId: Long,
    val userId: Long?,
    val userNickname: String?,
    val galleryId: Long?,
    val galleryTitle: String?,
    val consumedAt: ZonedDateTime,
    val expiresAt: ZonedDateTime,
)
