package com.soma.wes.billing.service

import com.soma.wes.billing.config.BillingProperties
import com.soma.wes.billing.dto.request.CheckoutRequest
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.TestCheckoutRepository
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.service.PersonalGalleryService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.temporal.ChronoField

@IntegrationTest
class BillingServiceTest @Autowired constructor(
    private val userFixture: UserFixture,
    private val userRepository: UserRepository,
    private val checkoutRepository: TestCheckoutRepository,
    private val personalGalleryService: PersonalGalleryService,
    private val clock: Clock,
) {
    private fun service(
        properties: BillingProperties = BillingProperties(testCheckoutEnabled = true),
        billingClock: Clock = clock,
    ): BillingService = BillingService(properties, userRepository, checkoutRepository, billingClock)

    private fun deployedProperties(vararg overrides: String): BillingProperties {
        var properties: BillingProperties? = null
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BillingConfig::class.java)
            .withPropertyValues(
                "spring.config.location=classpath:/config/application-variable.yml",
                "spring.profiles.active=prod",
                *overrides,
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                properties = context.getBean(BillingProperties::class.java)
            }
        return checkNotNull(properties)
    }

    @Test
    fun `테스트 결제는 실제 금전 처리 없이 본인 이용권을 저장한다`() {
        // given
        val user = userFixture.사용자()
        val target = service()
        // when
        val result = target.checkout(user.requiredId, CheckoutRequest("test-30-days"))
        // then
        assertThat(result.mode).isEqualTo("TEST")
        assertThat(result.status).isEqualTo("COMPLETED")
        assertThat(checkoutRepository.findById(result.checkoutId)).isPresent
    }

    @Test
    fun `배포 프로필의 테스트 플랜으로 개인 갤러리까지 시작할 수 있다`() {
        // given
        val user = userFixture.사용자()
        val nanosecondClock = Clock.fixed(clock.instant().with(ChronoField.NANO_OF_SECOND, 123456100), clock.zone)
        val target = service(deployedProperties(), nanosecondClock)
        // when
        val plans = target.getPlans()
        val checkout = target.checkout(user.requiredId, CheckoutRequest(plans.plans.single().id))
        val gallery = personalGalleryService.create(
            user.requiredId,
            CreatePersonalGalleryRequest(
                checkoutId = checkout.checkoutId,
                title = "테스트 개인 갤러리",
                selectionDeadline = checkout.expiresAt,
            ),
        )
        // then
        assertThat(plans.mode).isEqualTo("TEST")
        assertThat(plans.testCheckoutEnabled).isTrue()
        assertThat(checkout.mode).isEqualTo("TEST")
        assertThat(checkout.status).isEqualTo("COMPLETED")
        assertThat(checkout.amount).isZero()
        assertThat(gallery.status).isEqualTo(GalleryStatus.OPEN)
        assertThat(gallery.planMaxPhotoCount).isEqualTo(plans.plans.single().maxPhotoCount)
        assertThat(checkoutRepository.findById(checkout.checkoutId).orElseThrow().galleryId).isEqualTo(gallery.id)
    }

    @Test
    fun `배포 설정을 끄면 테스트 이용권을 발급하지 않는다`() {
        // given
        val user = userFixture.사용자()
        val target = service(deployedProperties("app.billing.test-checkout-enabled=false"))
        // when & then
        assertThat(target.getPlans().testCheckoutEnabled).isFalse()
        assertThatThrownBy { target.checkout(user.requiredId, CheckoutRequest("test-30-days")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_DISABLED)
        assertThat(checkoutRepository.count()).isZero()
    }

    @Test
    fun `설정을 명시적으로 켜지 않으면 기본적으로 결제하지 않는다`() {
        // given
        val user = userFixture.사용자()
        // when & then
        assertThatThrownBy { service(BillingProperties()).checkout(user.requiredId, CheckoutRequest("test-30-days")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_DISABLED)
    }

    @Test
    fun `다른 사람의 결제 내역은 조회할 수 없다`() {
        // given
        val owner = userFixture.사용자()
        val other = userFixture.사용자()
        val target = service()
        val checkout = target.checkout(owner.requiredId, CheckoutRequest("test-30-days"))
        // when & then
        assertThatThrownBy { target.getCheckout(checkout.checkoutId, other.requiredId) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_NOT_FOUND)
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties::class)
private class BillingConfig
