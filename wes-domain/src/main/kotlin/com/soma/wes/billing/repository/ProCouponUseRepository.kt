package com.soma.wes.billing.repository

import com.soma.wes.billing.domain.ProCouponUse
import org.springframework.data.jpa.repository.JpaRepository

interface ProCouponUseRepository : JpaRepository<ProCouponUse, Long> {
    fun findFirstByCouponIdAndUserIdOrderByConsumedAtDescIdDesc(couponId: Long, userId: Long): ProCouponUse?
    fun findAllByCouponIdInAndUserIdOrderByConsumedAtDescIdDesc(couponIds: Collection<Long>, userId: Long): List<ProCouponUse>
}
