package com.soma.wes.billing.service

import com.soma.wes.billing.domain.TestCheckout
import com.soma.wes.billing.dto.request.CheckoutRequest
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.TestCheckoutRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.ZonedDateTime

@IntegrationTest
class BillingServiceTest @Autowired constructor(
    private val target: BillingService,
    private val userFixture: UserFixture,
    private val checkoutRepository: TestCheckoutRepository,
    private val clock: Clock,
) {
    @Test
    fun `무료와 프로 정책을 제공하고 카드 결제는 준비 중이다`() {
        // when
        val result = target.getPlans()
        val free = result.plans.single { it.id == "free" }
        val pro = result.plans.single { it.id == "pro" }

        // then
        assertSoftly { softly ->
            softly.assertThat(result.mode).isEqualTo("COUPON")
            softly.assertThat(result.cardPaymentStatus).isEqualTo("COMING_SOON")
            softly.assertThat(result.testCheckoutEnabled).isFalse()
            softly.assertThat(free.maxPhotoCount).isEqualTo(500)
            softly.assertThat(free.durationMonths).isEqualTo(1L)
            softly.assertThat(free.durationDays).isNull()
            softly.assertThat(free.oncePerAccount).isTrue()
            softly.assertThat(pro.maxPhotoCount).isEqualTo(10_000)
            softly.assertThat(pro.durationDays).isNull()
            softly.assertThat(pro.durationMonths).isEqualTo(12L)
            softly.assertThat(pro.amount).isNull()
            softly.assertThat(pro.couponRequired).isTrue()
        }
    }

    @Test
    fun `이전 테스트 결제 API로도 이용권을 발급할 수 없다`() {
        // given
        val user = userFixture.사용자()

        // when & then
        assertThatThrownBy { target.checkout(user.requiredId, CheckoutRequest("pro")) }
            .isInstanceOf(BillingException::class.java)
            .extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_DISABLED)
        assertThat(checkoutRepository.count()).isZero()
    }

    @Test
    fun `이전에 발급한 이용권은 본인만 조회한다`() {
        // given
        val owner = userFixture.사용자()
        val other = userFixture.사용자()
        val checkout = checkoutRepository.save(TestCheckout(
            userId = owner.requiredId, planId = "legacy", amount = 0, currency = "KRW",
            maxPhotoCount = 100, expiresAt = ZonedDateTime.now(clock).plusDays(30),
        ))

        // when & then
        assertThat(target.getCheckout(checkout.id, owner.requiredId).checkoutId).isEqualTo(checkout.id)
        assertThatThrownBy { target.getCheckout(checkout.id, other.requiredId) }
            .isInstanceOf(BillingException::class.java)
            .extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_NOT_FOUND)
    }
}
