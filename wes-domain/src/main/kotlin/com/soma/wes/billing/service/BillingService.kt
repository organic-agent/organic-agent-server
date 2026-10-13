package com.soma.wes.billing.service

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

@Service
class BillingService(
    private val userRepository: UserRepository,
    private val checkoutRepository: TestCheckoutRepository,
) {
    fun getPlans(): PlansResponse = PlansResponse()

    /** 카드 결제 준비 중에는 이전 테스트 발급 API로도 프로 이용권을 얻을 수 없다. */
    @Suppress("UNUSED_PARAMETER")
    @Transactional(readOnly = true)
    fun checkout(userId: Long, request: CheckoutRequest): CheckoutResponse {
        userRepository.requireById(userId)
        throw BillingException(BillingErrorCode.CHECKOUT_DISABLED)
    }

    /** 이미 발급한 테스트 이용권의 조회는 유지한다. */
    @Transactional(readOnly = true)
    fun getCheckout(checkoutId: String, userId: Long): CheckoutResponse {
        userRepository.requireById(userId)

        return CheckoutResponse.from(
            checkoutRepository.findByIdAndUserId(id = checkoutId, userId = userId)
                ?: throw BillingException(BillingErrorCode.CHECKOUT_NOT_FOUND),
        )
    }
}
