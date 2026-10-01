package com.soma.wes.billing.service

import com.soma.wes.billing.dto.request.RegisterProCouponRequest
import com.soma.wes.billing.dto.response.MyBenefitsResponse
import com.soma.wes.billing.dto.response.ProCouponResponse
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.FreeGalleryClaimRepository
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.support.CouponCodes
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import com.soma.wes.user.repository.requireWithLockById
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Service
class CouponService(
    private val userRepository: UserRepository,
    private val coupons: ProCouponRepository,
    private val freeClaims: FreeGalleryClaimRepository,
    private val codes: CouponCodes,
    private val clock: Clock,
) {
    /** 선물 링크 재방문·통신 재시도는 같은 계정의 등록 결과를 돌려주며, 기간은 갤러리 개설까지 시작하지 않는다. */
    @Transactional
    fun register(userId: Long, request: RegisterProCouponRequest): ProCouponResponse {
        userRepository.requireWithLockById(userId)

        val coupon = coupons.findWithLockByCodeHash(codes.hash(request.code))
            ?: throw BillingException(BillingErrorCode.COUPON_NOT_FOUND)
        coupon.register(userId = userId, at = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS))
        return ProCouponResponse.from(coupon)
    }

    /** 무료 개설 가능 여부와 본인의 쿠폰만 반환한다. */
    @Transactional(readOnly = true)
    fun getMyBenefits(userId: Long): MyBenefitsResponse {
        userRepository.requireById(userId)

        return MyBenefitsResponse(
            freePlanAvailable = !freeClaims.existsByUserId(userId),
            coupons = coupons.findAllByUserIdOrderByRegisteredAtDescIdDesc(userId).map(ProCouponResponse::from),
        )
    }
}
