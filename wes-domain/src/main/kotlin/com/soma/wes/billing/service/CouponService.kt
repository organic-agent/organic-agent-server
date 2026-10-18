package com.soma.wes.billing.service

import com.soma.wes.billing.domain.ProCouponRegistration
import com.soma.wes.billing.dto.request.RegisterProCouponRequest
import com.soma.wes.billing.dto.request.ResolveProCouponRequest
import com.soma.wes.billing.dto.response.MyBenefitsResponse
import com.soma.wes.billing.dto.response.ProCouponLinkResponse
import com.soma.wes.billing.dto.response.ProCouponResponse
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.FreeGalleryClaimRepository
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.repository.ProCouponRegistrationRepository
import com.soma.wes.billing.repository.ProCouponUseRepository
import com.soma.wes.billing.support.CouponCodes
import com.soma.wes.gallery.support.GalleryAccessPolicy
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
    private val galleryAccess: GalleryAccessPolicy,
    private val clock: Clock,
    private val registrations: ProCouponRegistrationRepository,
    private val uses: ProCouponUseRepository,
) {
    /** 공유 코드는 본인의 사용 여부만 판단하며 링크 조회로 등록·사용하지 않는다. */
    @Transactional(readOnly = true)
    fun resolve(userId: Long, request: ResolveProCouponRequest): ProCouponLinkResponse {
        userRepository.requireById(userId)

        val coupon = coupons.findByCodeHash(codeHash = codes.hash(request.code))
            ?: throw BillingException(BillingErrorCode.COUPON_NOT_FOUND)
        val usage = if (coupon.reusable) uses.findFirstByCouponIdAndUserIdOrderByConsumedAtDescIdDesc(coupon.requiredId, userId) else null
        val consumed = if (coupon.reusable) usage != null else coupon.consumedAt != null
        val status = ProCouponResponse.status(coupon = coupon, used = consumed, at = ZonedDateTime.now(clock))
        val usedGalleryId = if (coupon.reusable) usage?.galleryId else coupon.galleryId
        val galleryId = usedGalleryId?.takeIf {
            status == "USED" && galleryAccess.canViewMetadata(galleryId = it, userId = userId)
        }
        return ProCouponLinkResponse.of(status = status, galleryId = galleryId)
    }

    /** 같은 계정의 등록 재시도는 한 행만 남긴다. 공유 코드의 계정별 등록은 서로 독립적이다. */
    @Transactional
    fun register(userId: Long, request: RegisterProCouponRequest): ProCouponResponse {
        userRepository.requireWithLockById(userId)

        val coupon = coupons.findWithLockByCodeHash(codes.hash(request.code))
            ?: throw BillingException(BillingErrorCode.COUPON_NOT_FOUND)
        val at = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        coupon.requireRedeemable(at)
        if (!coupon.reusable) {
            coupon.register(userId = userId, at = at)
            return ProCouponResponse.from(coupon = coupon, at = at)
        }

        val registration = registrations.findByCouponIdAndUserId(couponId = coupon.requiredId, userId = userId)
            ?: registrations.save(ProCouponRegistration(couponId = coupon.requiredId, userId = userId))
        val usage = uses.findFirstByCouponIdAndUserIdOrderByConsumedAtDescIdDesc(couponId = coupon.requiredId, userId = userId)
        return ProCouponResponse.from(coupon = coupon, at = at, registration = registration, usage = usage)
    }

    /** 다른 계정의 등록·사용 이력을 포함하지 않으며 공유 코드 조회는 일괄 조립한다. */
    @Transactional(readOnly = true)
    fun getMyBenefits(userId: Long): MyBenefitsResponse {
        userRepository.requireById(userId)

        val at = ZonedDateTime.now(clock)
        val registered = registrations.findAllByUserIdOrderByCreatedAtDescIdDesc(userId)
        val ids = registered.map { it.couponId }
        val shared = coupons.findAllById(ids).associateBy { it.requiredId }
        val latest = uses.findAllByCouponIdInAndUserIdOrderByConsumedAtDescIdDesc(couponIds = ids, userId = userId)
            .groupBy { it.couponId }.mapValues { (_, history) -> history.first() }
        val benefits = coupons.findAllByUserIdOrderByRegisteredAtDescIdDesc(userId).map { ProCouponResponse.from(coupon = it, at = at) } +
            registered.map { registration ->
                ProCouponResponse.from(coupon = shared.getValue(registration.couponId), at = at, registration = registration, usage = latest[registration.couponId])
            }
        return MyBenefitsResponse(
            freePlanAvailable = !freeClaims.existsByUserId(userId),
            coupons = benefits.sortedByDescending { it.registeredAt },
        )
    }
}
