package com.soma.wes.billing.support

import com.soma.wes.billing.domain.FreeGalleryClaim
import com.soma.wes.billing.domain.GalleryPlan
import com.soma.wes.billing.dto.GalleryPassDto
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.FreeGalleryClaimRepository
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.repository.TestCheckoutRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

@Service
class GalleryPasses(
    private val freeClaims: FreeGalleryClaimRepository,
    private val coupons: ProCouponRepository,
    private val checkouts: TestCheckoutRepository,
) {
    /** 호출자가 사용자 행을 잠근 트랜잭션에서만 실행해 무료 개설과 쿠폰 사용을 직렬화한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun prepare(
        userId: Long,
        planId: String?,
        couponId: Long?,
        checkoutId: String?,
        at: ZonedDateTime,
    ): GalleryPassDto {
        if (checkoutId != null) {
            if (planId != null || couponId != null) throw BillingException(BillingErrorCode.INVALID_PLAN_REQUEST)
            return legacyPass(userId = userId, checkoutId = checkoutId, at = at)
        }

        val plan = GalleryPlan.entries.find { it.planId == (planId ?: GalleryPlan.FREE.planId) }
            ?: throw BillingException(BillingErrorCode.PLAN_NOT_FOUND)
        val coupon = when (plan) {
            GalleryPlan.FREE -> {
                if (couponId != null) throw BillingException(BillingErrorCode.INVALID_PLAN_REQUEST)
                if (freeClaims.existsByUserId(userId)) throw BillingException(BillingErrorCode.FREE_PLAN_ALREADY_USED)
                null
            }
            GalleryPlan.PRO -> {
                val id = couponId ?: throw BillingException(BillingErrorCode.PRO_COUPON_REQUIRED)
                val found = coupons.findWithLockByIdAndUserId(id = id, userId = userId)
                    ?: throw BillingException(BillingErrorCode.COUPON_NOT_FOUND)
                found.requireUnused()
                found
            }
        }
        return GalleryPassDto(plan = plan, expiresAt = plan.expiresAt(at), maxPhotoCount = plan.maxPhotoCount, coupon = coupon)
    }

    /** 이전에 발급한 테스트 이용권은 기존 만료일·한도를 유지한다. 새 테스트 이용권은 발급하지 않는다. */
    private fun legacyPass(userId: Long, checkoutId: String, at: ZonedDateTime): GalleryPassDto {
        val checkout = checkouts.findWithLockByIdAndUserId(id = checkoutId, userId = userId)
            ?: throw BillingException(BillingErrorCode.CHECKOUT_NOT_FOUND)
        if (checkout.consumedAt != null) throw BillingException(BillingErrorCode.CHECKOUT_ALREADY_USED)
        if (!checkout.expiresAt.isAfter(at)) throw BillingException(BillingErrorCode.CHECKOUT_EXPIRED)
        return GalleryPassDto(plan = null, expiresAt = checkout.expiresAt, maxPhotoCount = checkout.maxPhotoCount, checkout = checkout)
    }

    /** 갤러리 저장과 같은 트랜잭션이므로 개설 실패는 무료 사용 이력·쿠폰 소비까지 함께 되돌린다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun consume(userId: Long, galleryId: Long, pass: GalleryPassDto, at: ZonedDateTime) {
        when (pass.plan) {
            GalleryPlan.FREE -> freeClaims.save(FreeGalleryClaim.of(userId = userId, galleryId = galleryId))
            GalleryPlan.PRO -> {
                val coupon = checkNotNull(pass.coupon) { "프로 이용권에 등록한 쿠폰이 없다." }
                coupon.consume(galleryId = galleryId, at = at, expiresAt = pass.expiresAt)
            }
            null -> {
                val checkout = checkNotNull(pass.checkout) { "기존 이용권에는 결제 기록이 있어야 한다." }
                checkout.galleryId = galleryId
                checkout.consumedAt = at
            }
        }
    }
}
