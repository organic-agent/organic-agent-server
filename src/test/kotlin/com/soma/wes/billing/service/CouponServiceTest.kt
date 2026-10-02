package com.soma.wes.billing.service

import com.soma.wes.billing.dto.request.RegisterProCouponRequest
import com.soma.wes.billing.dto.response.ProCouponResponse
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.support.CouponCodes
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.service.PersonalGalleryService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@IntegrationTest
class CouponServiceTest @Autowired constructor(
    private val target: CouponService,
    private val users: UserFixture,
    private val billing: BillingFixture,
    private val coupons: ProCouponRepository,
    private val codes: CouponCodes,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val galleries: PersonalGalleryService,
) {
    @Nested
    @DisplayName("코드를 등록할 때")
    inner class Register {
        @Test
        fun `코드 한 개를 미사용 쿠폰 한 장으로 보관하고 기간은 시작하지 않는다`() {
            // given
            val user = users.사용자()
            val issued = billing.미등록_프로_코드()

            // when
            val result = target.register(user.requiredId, RegisterProCouponRequest("  ${issued.code.lowercase()}  "))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.couponId).isEqualTo(issued.id)
                softly.assertThat(result.status).isEqualTo("AVAILABLE")
                softly.assertThat(result.expiresAt).isNull()
                softly.assertThat(result.consumedAt).isNull()
                softly.assertThat(result.galleryId).isNull()
                softly.assertThat(coupons.count()).isEqualTo(1L)
            }
        }

        @Test
        fun `선물 링크를 다시 열어도 본인은 같은 쿠폰을 받고 다른 사람은 등록할 수 없다`() {
            // given
            val owner = users.사용자()
            val other = users.사용자()
            val code = billing.미등록_프로_코드().code
            val first = target.register(owner.requiredId, RegisterProCouponRequest(code))

            // when
            val repeated = target.register(owner.requiredId, RegisterProCouponRequest(code.lowercase()))

            // then
            assertSoftly { softly ->
                softly.assertThat(repeated.couponId).isEqualTo(first.couponId)
                softly.assertThat(repeated.registeredAt.toInstant()).isEqualTo(first.registeredAt.toInstant())
                softly.assertThat(repeated.status).isEqualTo("AVAILABLE")
                softly.assertThat(repeated.expiresAt).isNull()
                softly.assertThat(coupons.count()).isEqualTo(1L)
            }
            assertThatThrownBy { target.register(other.requiredId, RegisterProCouponRequest(code)) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_CODE_ALREADY_REGISTERED)
            assertThat(target.getMyBenefits(other.requiredId).coupons).isEmpty()
        }

        @Test
        fun `같은 계정의 동시 수령 재시도는 한 쿠폰의 같은 등록 결과를 반환한다`() {
            // given
            val owner = users.사용자()
            val code = billing.미등록_프로_코드().code
            val start = CountDownLatch(1)

            // when
            val results = Executors.newFixedThreadPool(2).use { executor ->
                val futures = (1..2).map { executor.submit<ProCouponResponse> {
                    start.await()
                    target.register(owner.requiredId, RegisterProCouponRequest(code))
                } }
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            }

            // then
            assertThat(results[0].couponId).isEqualTo(results[1].couponId)
            assertThat(coupons.count()).isEqualTo(1L)
            assertThat(target.getMyBenefits(owner.requiredId).coupons).hasSize(1)
        }

        @Test
        fun `사용한 선물 링크를 다시 열면 사용한 갤러리를 반환하고 기간을 연장하지 않는다`() {
            // given
            val owner = users.사용자()
            val issued = billing.미등록_프로_코드()
            target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
            val gallery = galleries.create(
                userId = owner.requiredId,
                request = CreatePersonalGalleryRequest(title = "선물 갤러리", planId = "pro", couponId = issued.id),
            )

            // when
            val result = target.register(owner.requiredId, RegisterProCouponRequest(issued.code))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo("USED")
                softly.assertThat(result.galleryId).isEqualTo(gallery.id)
                softly.assertThat(result.expiresAt?.toInstant()).isEqualTo(gallery.planExpiresAt?.toInstant())
                softly.assertThat(coupons.count()).isEqualTo(1L)
            }
        }

        @Test
        fun `등록 계정이 삭제되어도 같은 코드를 다시 배포할 수 없다`() {
            // given
            val owner = users.사용자()
            val other = users.사용자()
            val code = billing.미등록_프로_코드().code
            target.register(owner.requiredId, RegisterProCouponRequest(code))
            jdbc.update("DELETE FROM users WHERE id = ?", owner.requiredId)

            // when & then
            assertThatThrownBy { target.register(other.requiredId, RegisterProCouponRequest(code)) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_CODE_ALREADY_REGISTERED)
        }

        @Test
        fun `발급하지 않은 코드와 잘못된 형식은 등록하지 않는다`() {
            // given
            val user = users.사용자()

            // when & then
            assertThatThrownBy { target.register(user.requiredId, RegisterProCouponRequest(codes.generate())) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_NOT_FOUND)
            for (code in listOf("", "bad-code", "x".repeat(81))) {
                assertThatThrownBy { target.register(user.requiredId, RegisterProCouponRequest(code)) }
                    .isInstanceOf(BillingException::class.java)
                    .extracting("errorCode").isEqualTo(BillingErrorCode.INVALID_COUPON_CODE)
            }
            assertThat(coupons.count()).isZero()
        }

        @Test
        fun `서로 다른 계정의 동시 등록은 한 명만 성공한다`() {
            // given
            val owners = listOf(users.사용자(), users.사용자())
            val code = billing.미등록_프로_코드().code
            val start = CountDownLatch(1)

            // when
            val results = Executors.newFixedThreadPool(2).use { executor ->
                val futures = owners.map { user -> executor.submit<Result<Long>> {
                    start.await()
                    runCatching { target.register(user.requiredId, RegisterProCouponRequest(code)).couponId }
                } }
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            }

            // then
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            val failure = checkNotNull(results.single { it.isFailure }.exceptionOrNull())
            assertThat(failure).isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_CODE_ALREADY_REGISTERED)
        }
    }

    @Nested
    @DisplayName("코드가 비활성화되어 있을 때")
    inner class Disabled {
        @Test
        fun `미등록 비활성 코드는 등록하지 못하고 재활성화 후에 등록할 수 있다`() {
            // given
            val owner = users.사용자()
            val issued = billing.미등록_프로_코드()
            val coupon = coupons.findById(issued.id).orElseThrow()
            coupon.changeEnabled(false, ZonedDateTime.now(clock))
            val disabled = coupons.saveAndFlush(coupon)

            // when & then
            assertThatThrownBy { target.register(owner.requiredId, RegisterProCouponRequest(issued.code)) }
                .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_DISABLED)
            assertThat(target.getMyBenefits(owner.requiredId).coupons).isEmpty()
            disabled.changeEnabled(true, ZonedDateTime.now(clock))
            coupons.saveAndFlush(disabled)
            assertThat(target.register(owner.requiredId, RegisterProCouponRequest(issued.code)).status).isEqualTo("AVAILABLE")
        }

        @Test
        fun `등록된 비활성 쿠폰은 보유 목록에 사용 불가 상태로 표시한다`() {
            // given
            val owner = users.사용자()
            val coupon = billing.미사용_프로_쿠폰(owner.requiredId)
            coupon.changeEnabled(false, ZonedDateTime.now(clock))
            coupons.saveAndFlush(coupon)

            // when
            val result = target.getMyBenefits(owner.requiredId).coupons.single()

            // then
            assertThat(result.status).isEqualTo("DISABLED")
            assertThat(result.expiresAt).isNull()
        }
    }

    @Nested
    @DisplayName("내 이용권을 조회할 때")
    inner class Benefits {
        @Test
        fun `내 쿠폰만 보이고 처음에는 무료 갤러리를 개설할 수 있다`() {
            // given
            val owner = users.사용자()
            val other = users.사용자()
            val mine = billing.미사용_프로_쿠폰(owner.requiredId)
            billing.미사용_프로_쿠폰(other.requiredId)

            // when
            val result = target.getMyBenefits(owner.requiredId)

            // then
            assertThat(result.freePlanAvailable).isTrue()
            assertThat(result.coupons.map { it.couponId }).containsExactly(mine.requiredId)
        }
    }
}
