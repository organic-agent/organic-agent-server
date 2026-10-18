package com.soma.wes.billing.service

import com.soma.wes.billing.domain.CouponUsagePolicy
import com.soma.wes.billing.dto.request.RegisterProCouponRequest
import com.soma.wes.billing.dto.request.ResolveProCouponRequest
import com.soma.wes.billing.dto.response.ProCouponLinkResponse
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.repository.ProCouponUseRepository
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.service.PersonalGalleryService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@IntegrationTest
class ReusableCouponServiceTest @Autowired constructor(
    private val target: CouponService,
    private val users: UserFixture,
    private val billing: BillingFixture,
    private val coupons: ProCouponRepository,
    private val uses: ProCouponUseRepository,
    private val galleries: PersonalGalleryService,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val transactions: PlatformTransactionManager,
) {
    @Test
    fun `공유 반복 코드는 여러 계정과 같은 계정의 여러 갤러리에 쓰고 성공한 사용만 센다`() {
        // given
        val owner = users.사용자()
        val other = users.사용자()
        val issued = billing.미등록_프로_코드(usagePolicy = CouponUsagePolicy.UNLIMITED, code = "PARTNER_2026")
        target.register(owner.requiredId, RegisterProCouponRequest("partner_2026"))
        target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
        target.register(other.requiredId, RegisterProCouponRequest(issued.code))
        assertThat(coupons.findById(issued.id).orElseThrow().useCount).isZero()

        // when
        val first = galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "첫 갤러리", planId = "pro", couponId = issued.id))
        val second = galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "다음 갤러리", planId = "pro", couponId = issued.id))
        val third = galleries.create(other.requiredId, CreatePersonalGalleryRequest(title = "다른 계정", planId = "pro", couponId = issued.id))
        val benefit = target.getMyBenefits(owner.requiredId).coupons.single()

        // then
        assertSoftly { softly ->
            softly.assertThat(listOf(first.id, second.id, third.id).distinct()).hasSize(3)
            softly.assertThat(coupons.findById(issued.id).orElseThrow().useCount).isEqualTo(3L)
            softly.assertThat(uses.count()).isEqualTo(3L)
            softly.assertThat(benefit.status).isEqualTo("AVAILABLE")
            softly.assertThat(benefit.galleryId).isEqualTo(second.id)
            softly.assertThat(target.resolve(owner.requiredId, ResolveProCouponRequest(issued.code)).galleryId).isNull()
            softly.assertThat(target.getMyBenefits(other.requiredId).coupons.single().galleryId).isEqualTo(third.id)
        }
    }

    @Test
    fun `계정당 1회 코드는 다른 계정도 쓸 수 있지만 갤러리를 지워도 같은 계정은 다시 못 쓴다`() {
        // given
        val owner = users.사용자()
        val other = users.사용자()
        val issued = billing.미등록_프로_코드(usagePolicy = CouponUsagePolicy.ONCE_PER_ACCOUNT)
        target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
        target.register(other.requiredId, RegisterProCouponRequest(issued.code))
        val first = galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "한 번", planId = "pro", couponId = issued.id))
        galleries.create(other.requiredId, CreatePersonalGalleryRequest(title = "다른 계정", planId = "pro", couponId = issued.id))
        jdbc.update("DELETE FROM galleries WHERE id = ?", first.id)

        // when & then
        assertThatThrownBy {
            galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "두 번", planId = "pro", couponId = issued.id))
        }.isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_ALREADY_USED)
        assertThat(target.register(owner.requiredId, RegisterProCouponRequest(issued.code)).status).isEqualTo("USED")
        assertThat(coupons.findById(issued.id).orElseThrow().useCount).isEqualTo(2L)
        assertThat(uses.count()).isEqualTo(2L)
    }

    @Test
    fun `등록 뒤 마감일이 지나면 사용과 재등록을 막고 Pro 기간은 별도로 유지한다`() {
        // given
        val owner = users.사용자()
        val now = ZonedDateTime.now(clock)
        val issued = billing.미등록_프로_코드(usagePolicy = CouponUsagePolicy.UNLIMITED, redeemUntil = now.plusDays(1))
        target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
        galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "마감 전 사용", planId = "pro", couponId = issued.id))
        val expiresAt = uses.findAll().single().expiresAt
        jdbc.update("UPDATE pro_coupons SET redeem_until = ? WHERE id = ?", now.minusSeconds(1).toOffsetDateTime(), issued.id)

        // when & then
        assertThatThrownBy { target.register(owner.requiredId, RegisterProCouponRequest(issued.code)) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_EXPIRED)
        assertThatThrownBy {
            galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "마감 후 사용", planId = "pro", couponId = issued.id))
        }.isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_EXPIRED)
        assertSoftly { softly ->
            softly.assertThat(target.getMyBenefits(owner.requiredId).coupons.single().status).isEqualTo("EXPIRED")
            softly.assertThat(target.resolve(owner.requiredId, ResolveProCouponRequest(issued.code)).status).isEqualTo(ProCouponLinkResponse.Status.EXPIRED)
            softly.assertThat(uses.findAll().single().expiresAt).isEqualTo(expiresAt)
            softly.assertThat(expiresAt.isAfter(now.plusMonths(11))).isTrue()
            softly.assertThat(coupons.findById(issued.id).orElseThrow().useCount).isEqualTo(1L)
        }
    }

    @Test
    fun `동시 사용해도 계정당 1회 제한과 사용 횟수가 일치한다`() {
        // given
        val owner = users.사용자()
        val issued = billing.미등록_프로_코드(usagePolicy = CouponUsagePolicy.ONCE_PER_ACCOUNT)
        target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // when
        val results = try {
            val futures = (1..2).map { index -> executor.submit<Boolean> {
                start.await()
                runCatching { galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "동시 $index", planId = "pro", couponId = issued.id)) }.isSuccess
            } }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }

        // then
        assertThat(results.count { it }).isEqualTo(1)
        assertThat(uses.count()).isEqualTo(1L)
        assertThat(coupons.findById(issued.id).orElseThrow().useCount).isEqualTo(1L)
    }

    @Test
    fun `갤러리 생성 트랜잭션이 실패하면 사용 기록과 횟수를 함께 되돌린다`() {
        // given
        val owner = users.사용자()
        val issued = billing.미등록_프로_코드(usagePolicy = CouponUsagePolicy.UNLIMITED)
        target.register(owner.requiredId, RegisterProCouponRequest(issued.code))

        // when & then
        assertThatThrownBy {
            TransactionTemplate(transactions).execute {
                galleries.create(owner.requiredId, CreatePersonalGalleryRequest(title = "롤백", planId = "pro", couponId = issued.id))
                error("테스트 롤백")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(uses.count()).isZero()
        assertThat(coupons.findById(issued.id).orElseThrow().useCount).isZero()
    }
}
