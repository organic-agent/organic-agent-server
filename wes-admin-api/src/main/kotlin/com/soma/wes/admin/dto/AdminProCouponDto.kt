package com.soma.wes.admin.dto

import com.soma.wes.admin.domain.AdminProCouponStatus
import java.time.ZonedDateTime

data class AdminProCouponDto(
    val couponId: Long,
    val version: Long,
    val codeSuffix: String?,
    val status: AdminProCouponStatus,
    val issuedByAdminId: Long?,
    val createdAt: ZonedDateTime,
    val userId: Long?,
    val userNickname: String?,
    val registeredAt: ZonedDateTime?,
    val consumedAt: ZonedDateTime?,
    val galleryId: Long?,
    val galleryTitle: String?,
    val expiresAt: ZonedDateTime?,
    val disabledAt: ZonedDateTime?,
)
