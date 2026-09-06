package com.soma.wes.billing.service

import com.soma.wes.billing.config.BillingProperties
import com.soma.wes.billing.dto.request.CheckoutRequest
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.TestCheckoutRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.env.MockEnvironment
import java.time.Clock

@IntegrationTest
class BillingServiceTest @Autowired constructor(
    private val userFixture: UserFixture,
    private val userRepository: UserRepository,
    private val checkoutRepository: TestCheckoutRepository,
    private val clock: Clock,
) {
    private fun service(vararg profiles: String, enabled: Boolean = true): BillingService {
        val environment = MockEnvironment().apply { setActiveProfiles(*profiles) }
        return BillingService(BillingProperties(testCheckoutEnabled = enabled), environment, userRepository, checkoutRepository, clock)
    }

    @Test
    fun `테스트 결제는 실제 금전 처리 없이 본인 이용권을 저장한다`() {
        // given
        val user = userFixture.사용자()
        val target = service("local")
        // when
        val result = target.checkout(user.requiredId, CheckoutRequest("test-30-days"))
        // then
        assertThat(result.mode).isEqualTo("TEST")
        assertThat(result.status).isEqualTo("COMPLETED")
        assertThat(checkoutRepository.findById(result.checkoutId)).isPresent
    }

    @Test
    fun `prod가 포함되면 local과 활성 설정이 있어도 결제하지 않는다`() {
        // given
        val user = userFixture.사용자()
        val target = service("local", "prod")
        // when & then
        assertThat(target.getPlans().testCheckoutEnabled).isFalse()
        assertThatThrownBy { target.checkout(user.requiredId, CheckoutRequest("test-30-days")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_DISABLED)
        assertThat(checkoutRepository.count()).isZero()
    }

    @Test
    fun `테스트 프로필도 명시적으로 켜지 않으면 결제하지 않는다`() {
        // given
        val user = userFixture.사용자()
        // when & then
        assertThatThrownBy { service("test", enabled = false).checkout(user.requiredId, CheckoutRequest("test-30-days")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_DISABLED)
    }

    @Test
    fun `다른 사람의 결제 내역은 조회할 수 없다`() {
        // given
        val owner = userFixture.사용자()
        val other = userFixture.사용자()
        val target = service("test")
        val checkout = target.checkout(owner.requiredId, CheckoutRequest("test-30-days"))
        // when & then
        assertThatThrownBy { target.getCheckout(checkout.checkoutId, other.requiredId) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_NOT_FOUND)
    }
}
