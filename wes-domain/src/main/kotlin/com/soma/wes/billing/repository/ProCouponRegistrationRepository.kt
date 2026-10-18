package com.soma.wes.billing.repository

import com.soma.wes.billing.domain.ProCouponRegistration
import org.springframework.data.jpa.repository.JpaRepository

interface ProCouponRegistrationRepository : JpaRepository<ProCouponRegistration, Long> {
    fun findByCouponIdAndUserId(couponId: Long, userId: Long): ProCouponRegistration?
    fun findAllByUserIdOrderByCreatedAtDescIdDesc(userId: Long): List<ProCouponRegistration>
}
