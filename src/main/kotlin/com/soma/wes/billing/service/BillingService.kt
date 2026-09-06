package com.soma.wes.billing.service

import com.soma.wes.billing.config.BillingProperties
import com.soma.wes.billing.domain.TestCheckout
import com.soma.wes.billing.dto.request.CheckoutRequest
import com.soma.wes.billing.dto.response.CheckoutResponse
import com.soma.wes.billing.dto.response.PlansResponse
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.TestCheckoutRepository
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Service
class BillingService(
    private val properties: BillingProperties,
    private val userRepository: UserRepository,
    private val checkoutRepository: TestCheckoutRepository,
    private val clock: Clock,
) {
    fun getPlans(): PlansResponse {
        validatePlans()
        val enabled = isTestCheckoutEnabled()
        return PlansResponse(mode = if (enabled) "TEST" else "DISABLED", testCheckoutEnabled = enabled, plans = properties.plans)
    }

    @Transactional
    fun checkout(userId: Long, request: CheckoutRequest): CheckoutResponse {
        userRepository.requireById(userId)
        if (!isTestCheckoutEnabled()) throw BillingException(BillingErrorCode.CHECKOUT_DISABLED)
        validatePlans()
        val plan = properties.plans.find { it.id == request.planId }
            ?: throw BillingException(BillingErrorCode.PLAN_NOT_FOUND)
        // 응답의 만료 시각을 완료일로 다시 보내도 PostgreSQL에 저장된 값보다 늦어지지 않아야 한다.
        val expiresAt = ZonedDateTime.now(clock).plusDays(plan.durationDays.toLong()).truncatedTo(ChronoUnit.MICROS)
        val checkout = checkoutRepository.save(TestCheckout(
            userId = userId, planId = plan.id, amount = plan.amount, currency = plan.currency,
            maxPhotoCount = plan.maxPhotoCount, expiresAt = expiresAt,
        ))
        return CheckoutResponse.from(checkout)
    }

    @Transactional(readOnly = true)
    fun getCheckout(checkoutId: String, userId: Long): CheckoutResponse {
        userRepository.requireById(userId)
        return CheckoutResponse.from(checkoutRepository.findByIdAndUserId(checkoutId, userId)
            ?: throw BillingException(BillingErrorCode.CHECKOUT_NOT_FOUND))
    }

    private fun isTestCheckoutEnabled(): Boolean = properties.testCheckoutEnabled

    private fun validatePlans() {
        if (properties.plans.map { it.id }.distinct().size != properties.plans.size || properties.plans.any {
            it.id.isBlank() || it.id.length > 100 || it.name.isBlank() || it.amount < 0 ||
                !it.currency.matches(Regex("[A-Z]{3}")) || it.durationDays !in 1..MAX_DURATION_DAYS || it.maxPhotoCount < 1
        }) throw BillingException(BillingErrorCode.INVALID_PLAN_CONFIGURATION)
    }

    companion object {
        /** 테스트 플랜의 입력 실수로 사실상 영구 이용권을 만들지 않는 상한. */
        private const val MAX_DURATION_DAYS = 3650
    }
}
