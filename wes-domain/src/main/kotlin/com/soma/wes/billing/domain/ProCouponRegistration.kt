package com.soma.wes.billing.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 공유 코드의 등록과 실제 사용은 별개다. 같은 계정의 등록 재시도는 이 행 하나를 재사용한다. */
@Entity
@Table(name = "pro_coupon_registrations")
class ProCouponRegistration(
    @Column(name = "coupon_id", nullable = false, updatable = false)
    val couponId: Long,
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
