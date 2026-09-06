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
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class BillingService(
    private val properties: BillingProperties,
    private val environment: Environment,
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
        val checkout = checkoutRepository.save(TestCheckout(
            userId = userId, planId = plan.id, amount = plan.amount, currency = plan.currency,
            maxPhotoCount = plan.maxPhotoCount, expiresAt = ZonedDateTime.now(clock).plusDays(plan.durationDays.toLong()),
        ))
        return CheckoutResponse.from(checkout)
    }

    @Transactional(readOnly = true)
    fun getCheckout(checkoutId: String, userId: Long): CheckoutResponse {
        userRepository.requireById(userId)
        return CheckoutResponse.from(checkoutRepository.findByIdAndUserId(checkoutId, userId)
            ?: throw BillingException(BillingErrorCode.CHECKOUT_NOT_FOUND))
    }

    private fun isTestCheckoutEnabled(): Boolean = properties.testCheckoutEnabled &&
        environment.activeProfiles.any { it == "local" || it == "test" } &&
        environment.activeProfiles.none { it == "prod" }

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
