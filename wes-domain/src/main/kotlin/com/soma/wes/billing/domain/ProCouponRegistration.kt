package com.soma.wes.billing.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 공유 코드의 등록과 실제 사용은 별개다. 같은 계정의 등록 재시도는 이 행 하나를 재사용한다. */
@Entity
@Table(name = "pro_coupon_registrations")
// 이 테이블은 감사 시각이 NOT NULL이다. BaseEntity의 공용 정의(NULL 허용)를 이 엔티티에서만 덮는다.
@AttributeOverride(name = "createdAt", column = Column(name = "created_at", nullable = false, updatable = false))
@AttributeOverride(name = "updatedAt", column = Column(name = "updated_at", nullable = false))
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
