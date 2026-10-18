package com.soma.wes.billing.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

/** 계정이나 갤러리를 영구 삭제해도 사용 횟수와 시각은 남긴다. */
@Entity
@Table(name = "pro_coupon_uses")
class ProCouponUse(
    @Column(name = "coupon_id", nullable = false, updatable = false)
    val couponId: Long,
    @Column(name = "user_id", nullable = true, updatable = false)
    val userId: Long?,
    @Column(name = "gallery_id", nullable = true, updatable = false)
    val galleryId: Long?,
    @Column(name = "consumed_at", nullable = false, updatable = false)
    val consumedAt: ZonedDateTime,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: ZonedDateTime,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
