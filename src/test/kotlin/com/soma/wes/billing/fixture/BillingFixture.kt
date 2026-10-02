package com.soma.wes.billing.fixture

import com.soma.wes.billing.domain.ProCoupon
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.support.CouponCodes
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZonedDateTime

@Component
class BillingFixture(
    private val coupons: ProCouponRepository,
    private val codes: CouponCodes,
    private val clock: Clock,
) {
    fun 미등록_프로_코드(): IssuedProCoupon {
        val code = codes.generate()
        val coupon = coupons.save(ProCoupon.of(codes.hash(code)))
        return IssuedProCoupon(id = coupon.requiredId, code = code)
    }

    fun 미사용_프로_쿠폰(userId: Long, registeredAt: ZonedDateTime = ZonedDateTime.now(clock)): ProCoupon {
        val code = codes.generate()
        val coupon = ProCoupon.of(codes.hash(code))
        coupon.register(userId = userId, at = registeredAt)
        return coupons.save(coupon)
    }
}
