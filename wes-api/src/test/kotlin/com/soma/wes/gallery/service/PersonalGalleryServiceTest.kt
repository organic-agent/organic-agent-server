package com.soma.wes.gallery.service

import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.billing.repository.FreeGalleryClaimRepository
import com.soma.wes.billing.service.CouponService
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.billing.domain.TestCheckout
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.TestCheckoutRepository
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.dto.request.UpdatePersonalGalleryRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.assertj.core.api.SoftAssertions.assertSoftly
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

@IntegrationTest
class PersonalGalleryServiceTest @Autowired constructor(
    private val target: PersonalGalleryService,
    private val userFixture: UserFixture,
    private val checkoutRepository: TestCheckoutRepository,
    private val members: WorkspaceMemberRepository,
    private val policy: GalleryAccessPolicy,
    private val billing: BillingFixture,
    private val freeClaims: FreeGalleryClaimRepository,
    private val benefits: CouponService,
    private val galleries: GalleryRepository,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    private fun checkout(userId: Long, expired: Boolean = false): TestCheckout = checkoutRepository.save(TestCheckout(
        userId = userId, planId = "test-plan", amount = 0, currency = "KRW", maxPhotoCount = 100,
        expiresAt = if (expired) ZonedDateTime.now().minusDays(1) else ZonedDateTime.now().plusDays(30),
    ))

    @Test
    fun `결제를 소비해 바로 접근 가능한 개인 갤러리를 만든다`() {
        // given
        val user = userFixture.사용자()
        val payment = checkout(user.requiredId)
        // when
        val result = target.create(user.requiredId, CreatePersonalGalleryRequest(payment.id, "우리 사진", maxSelectablePhotoCount = 20))
        // then
        assertThat(result.status).isEqualTo(GalleryStatus.OPEN)
        assertThat(result.stage).isEqualTo(GalleryStage.UPLOAD)
        assertThat(result.photoOrganizationRequired).isTrue()
        assertThat(result.planMaxPhotoCount).isEqualTo(100)
        assertThat(checkoutRepository.findById(payment.id).orElseThrow().galleryId).isEqualTo(result.id)
    }

    @Test
    fun `다른 사람 결제나 만료된 결제로 개설할 수 없다`() {
        // given
        val user = userFixture.사용자()
        val other = userFixture.사용자()
        val foreign = checkout(other.requiredId)
        val expired = checkout(user.requiredId, expired = true)
        // when & then
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(foreign.id, "외부 결제")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_NOT_FOUND)
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(expired.id, "만료 결제")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_EXPIRED)
    }

    @Test
    fun `사용한 결제로 갤러리를 두 번 만들지 않는다`() {
        // given
        val user = userFixture.사용자()
        val payment = checkout(user.requiredId)
        target.create(user.requiredId, CreatePersonalGalleryRequest(payment.id, "첫 갤러리"))
        // when & then
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(payment.id, "두 번째")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_ALREADY_USED)
    }

    @Test
    fun `파트너는 업로드와 선택과 보정 처리를 함께 한다`() {
        // given
        val owner = userFixture.사용자()
        val partner = userFixture.사용자()
        val gallery = target.create(owner.requiredId, CreatePersonalGalleryRequest(checkout(owner.requiredId).id, "함께 고르기"))
        members.save(WorkspaceMember(workspaceId = gallery.workspaceId, userId = partner.requiredId, role = WorkspaceRole.MEMBER))
        // when & then
        assertThat(policy.requireUploader(gallery.id, partner.requiredId).requiredId).isEqualTo(gallery.id)
        assertThat(policy.requireSelectionEditor(gallery.id, partner.requiredId).requiredId).isEqualTo(gallery.id)
        assertThat(policy.requireRetouchProcessor(gallery.id, partner.requiredId).requiredId).isEqualTo(gallery.id)
    }

    @Nested
    @DisplayName("갤러리 정보를 고칠 때")
    inner class Update {
        @Test
        fun `파트너도 이름과 목표일과 고를 장수를 고친다`() {
            // given
            val owner = userFixture.사용자()
            val partner = userFixture.사용자()
            val gallery = target.create(owner.requiredId, CreatePersonalGalleryRequest(title = "함께 고르기"))
            members.save(WorkspaceMember(workspaceId = gallery.workspaceId, userId = partner.requiredId, role = WorkspaceRole.MEMBER))
            val deadline = ZonedDateTime.now(clock).plusDays(7)

            // when
            val result = target.update(gallery.id, partner.requiredId, UpdatePersonalGalleryRequest(
                title = "파트너가 바꾼 이름", selectionDeadline = deadline, maxSelectablePhotoCount = 30,
            ))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.title).isEqualTo("파트너가 바꾼 이름")
                softly.assertThat(result.selectionDeadline).isEqualTo(deadline)
                softly.assertThat(result.maxSelectablePhotoCount).isEqualTo(30)
            }
        }

        @Test
        fun `갤러리 참여자가 아니면 고칠 수 없다`() {
            // given
            val owner = userFixture.사용자()
            val stranger = userFixture.사용자()
            val gallery = target.create(owner.requiredId, CreatePersonalGalleryRequest(title = "우리 갤러리"))

            // when & then
            assertThatThrownBy { target.update(gallery.id, stranger.requiredId, UpdatePersonalGalleryRequest("남이 바꾼 이름")) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `이용 기간 마지막 날은 그날 밤까지 목표일로 정할 수 있다`() {
            // given
            val owner = userFixture.사용자()
            val gallery = target.create(owner.requiredId, CreatePersonalGalleryRequest(title = "마지막 날"))
            val lastDayNight = checkNotNull(gallery.planExpiresAt).withZoneSameInstant(clock.zone)
                .toLocalDate().atTime(23, 59, 59).atZone(clock.zone)

            // when
            val result = target.update(gallery.id, owner.requiredId, UpdatePersonalGalleryRequest(
                title = "마지막 날", selectionDeadline = lastDayNight,
            ))

            // then
            assertThat(result.selectionDeadline).isEqualTo(lastDayNight)
        }

        @Test
        fun `이용 기간 다음 날을 목표일로 정하면 이용 기간 안으로 정하라고 거절한다`() {
            // given
            val owner = userFixture.사용자()
            val gallery = target.create(owner.requiredId, CreatePersonalGalleryRequest(title = "다음 날"))
            val nextDay = checkNotNull(gallery.planExpiresAt).withZoneSameInstant(clock.zone)
                .toLocalDate().plusDays(1).atStartOfDay(clock.zone)

            // when & then
            assertThatThrownBy { target.update(gallery.id, owner.requiredId, UpdatePersonalGalleryRequest(
                title = "다음 날", selectionDeadline = nextDay,
            )) }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_AFTER_PLAN_EXPIRY)
        }

        @Test
        fun `지난 날짜를 목표일로 정하면 현재보다 뒤여야 한다고 거절한다`() {
            // given
            val owner = userFixture.사용자()
            val gallery = target.create(owner.requiredId, CreatePersonalGalleryRequest(title = "지난 날"))

            // when & then
            assertThatThrownBy { target.update(gallery.id, owner.requiredId, UpdatePersonalGalleryRequest(
                title = "지난 날", selectionDeadline = ZonedDateTime.now(clock).minusDays(1),
            )) }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
        }
    }

    @Nested
    @DisplayName("무료 갤러리를 만들 때")
    inner class FreePlan {
        @Test
        fun `계정당 한 번 생성일부터 한 달과 500장을 제공한다`() {
            // given
            val user = userFixture.사용자()
            val before = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)

            // when
            val result = target.create(user.requiredId, CreatePersonalGalleryRequest(title = "무료 갤러리"))
            val after = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.planId).isEqualTo("free")
                softly.assertThat(result.planMaxPhotoCount).isEqualTo(500)
                softly.assertThat(result.planExpiresAt).isBetween(before.plusMonths(1), after.plusMonths(1))
                softly.assertThat(result.selectionDeadline).isEqualTo(result.planExpiresAt)
                softly.assertThat(benefits.getMyBenefits(user.requiredId).freePlanAvailable).isFalse()
            }
            assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(title = "두 번째 무료")) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.FREE_PLAN_ALREADY_USED)
        }

        @Test
        fun `무료 갤러리를 영구 삭제해도 다시 개설할 수 없다`() {
            // given
            val user = userFixture.사용자()
            val gallery = target.create(user.requiredId, CreatePersonalGalleryRequest(title = "삭제할 무료"))
            jdbc.update("DELETE FROM galleries WHERE id = ?", gallery.id)

            // when & then
            assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(title = "다시 무료")) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.FREE_PLAN_ALREADY_USED)
            assertThat(benefits.getMyBenefits(user.requiredId).freePlanAvailable).isFalse()
        }

        @Test
        fun `개설 실패는 무료 이용 기회를 소비하지 않는다`() {
            // given
            val user = userFixture.사용자()

            // when & then
            assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(
                title = "잘못된 마감", selectionDeadline = ZonedDateTime.now(clock).plusYears(1),
            )) }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_AFTER_PLAN_EXPIRY)
            assertThat(freeClaims.count()).isZero()
            assertThat(benefits.getMyBenefits(user.requiredId).freePlanAvailable).isTrue()
            assertThat(target.create(user.requiredId, CreatePersonalGalleryRequest(title = "정상 무료")).planId).isEqualTo("free")
        }

        @Test
        fun `같은 계정의 동시 무료 개설은 하나만 성공한다`() {
            // given
            val user = userFixture.사용자()

            // when
            val results = simultaneousCreates(user.requiredId, CreatePersonalGalleryRequest(title = "동시 무료"))

            // then
            assertSingleSuccess(results, BillingErrorCode.FREE_PLAN_ALREADY_USED)
            assertThat(freeClaims.count()).isEqualTo(1L)
            assertThat(galleries.count()).isEqualTo(1L)
        }
    }

    @Nested
    @DisplayName("프로 갤러리를 만들 때")
    inner class ProPlan {
        @Test
        fun `쿠폰 사용 시점부터 달력 기준 1년과 만 장을 제공한다`() {
            // given
            val user = userFixture.사용자()
            val coupon = billing.미사용_프로_쿠폰(userId = user.requiredId, registeredAt = ZonedDateTime.now(clock).minusYears(1))
            val before = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)

            // when
            val result = target.create(user.requiredId, CreatePersonalGalleryRequest(
                title = "프로 갤러리", planId = "pro", couponId = coupon.requiredId,
            ))
            val after = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
            val used = benefits.getMyBenefits(user.requiredId).coupons.single()

            // then
            assertSoftly { softly ->
                softly.assertThat(result.planId).isEqualTo("pro")
                softly.assertThat(result.planMaxPhotoCount).isEqualTo(10_000)
                softly.assertThat(result.planExpiresAt).isBetween(before.plusYears(1), after.plusYears(1))
                softly.assertThat(used.status).isEqualTo("USED")
                softly.assertThat(used.galleryId).isEqualTo(result.id)
                softly.assertThat(used.expiresAt).isEqualTo(result.planExpiresAt)
                softly.assertThat(benefits.getMyBenefits(user.requiredId).freePlanAvailable).isTrue()
            }
        }

        @Test
        fun `쿠폰 없이 프로를 개설하거나 다른 사람 쿠폰을 사용할 수 없다`() {
            // given
            val user = userFixture.사용자()
            val other = userFixture.사용자()
            val foreign = billing.미사용_프로_쿠폰(other.requiredId)

            // when & then
            assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(title = "쿠폰 없는 프로", planId = "pro")) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.PRO_COUPON_REQUIRED)
            assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(title = "남의 프로", planId = "pro", couponId = foreign.requiredId)) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_NOT_FOUND)
            assertThat(galleries.count()).isZero()
        }

        @Test
        fun `프로 쿠폰을 두 번 사용할 수 없고 삭제 후에도 재사용하지 않는다`() {
            // given
            val user = userFixture.사용자()
            val coupon = billing.미사용_프로_쿠폰(user.requiredId)
            val request = CreatePersonalGalleryRequest(title = "프로", planId = "pro", couponId = coupon.requiredId)
            val first = target.create(user.requiredId, request)
            jdbc.update("DELETE FROM galleries WHERE id = ?", first.id)

            // when & then
            assertThatThrownBy { target.create(user.requiredId, request) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_ALREADY_USED)
            assertThat(benefits.getMyBenefits(user.requiredId).coupons.single().status).isEqualTo("USED")
        }

        @Test
        fun `개설에 실패하면 쿠폰은 미사용 상태로 남는다`() {
            // given
            val user = userFixture.사용자()
            val coupon = billing.미사용_프로_쿠폰(user.requiredId)

            // when & then
            assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(
                title = "실패하는 프로", planId = "pro", couponId = coupon.requiredId,
                selectionDeadline = ZonedDateTime.now(clock).plusYears(2),
            )) }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_AFTER_PLAN_EXPIRY)
            assertThat(benefits.getMyBenefits(user.requiredId).coupons.single().status).isEqualTo("AVAILABLE")
            assertThat(target.create(user.requiredId, CreatePersonalGalleryRequest(
                title = "정상 프로", planId = "pro", couponId = coupon.requiredId,
            )).planId).isEqualTo("pro")
        }

        @Test
        fun `프로 개설은 기존 무료 갤러리를 업그레이드하지 않는다`() {
            // given
            val user = userFixture.사용자()
            val free = target.create(user.requiredId, CreatePersonalGalleryRequest(title = "원래 무료"))
            val coupon = billing.미사용_프로_쿠폰(user.requiredId)

            // when
            val pro = target.create(user.requiredId, CreatePersonalGalleryRequest(title = "새 프로", planId = "pro", couponId = coupon.requiredId))
            val original = galleries.findById(free.id).orElseThrow()

            // then
            assertSoftly { softly ->
                softly.assertThat(pro.id).isNotEqualTo(free.id)
                softly.assertThat(original.planMaxPhotoCount).isEqualTo(500)
                softly.assertThat(original.planExpiresAt).isEqualTo(free.planExpiresAt)
                softly.assertThat(original.planType?.planId).isEqualTo("free")
            }
        }

        @Test
        fun `동시에 같은 쿠폰으로 개설해도 갤러리는 하나만 생긴다`() {
            // given
            val user = userFixture.사용자()
            val coupon = billing.미사용_프로_쿠폰(user.requiredId)

            // when
            val results = simultaneousCreates(user.requiredId, CreatePersonalGalleryRequest(title = "동시 프로", planId = "pro", couponId = coupon.requiredId))

            // then
            assertSingleSuccess(results, BillingErrorCode.COUPON_ALREADY_USED)
            assertThat(galleries.count()).isEqualTo(1L)
        }
    }

    @Test
    fun `무료에 쿠폰을 섞거나 알 수 없는 요금제를 보내면 거절한다`() {
        // given
        val user = userFixture.사용자()
        val coupon = billing.미사용_프로_쿠폰(user.requiredId)

        // when & then
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(title = "잘못된 조합", planId = "free", couponId = coupon.requiredId)) }
            .isInstanceOf(BillingException::class.java)
            .extracting("errorCode").isEqualTo(BillingErrorCode.INVALID_PLAN_REQUEST)
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(title = "없는 요금제", planId = "premium")) }
            .isInstanceOf(BillingException::class.java)
            .extracting("errorCode").isEqualTo(BillingErrorCode.PLAN_NOT_FOUND)
    }

    private fun simultaneousCreates(userId: Long, request: CreatePersonalGalleryRequest): List<Result<Long>> {
        val start = CountDownLatch(1)
        return Executors.newFixedThreadPool(2).use { executor ->
            val futures = (1..2).map { executor.submit<Result<Long>> {
                start.await()
                runCatching { target.create(userId, request).id }
            } }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        }
    }

    private fun assertSingleSuccess(results: List<Result<Long>>, expectedError: BillingErrorCode) {
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(checkNotNull(results.single { it.isFailure }.exceptionOrNull()))
            .isInstanceOf(BillingException::class.java)
            .extracting("errorCode").isEqualTo(expectedError)
    }

    @Test
    fun `등록 후 비활성화된 쿠폰은 새 갤러리에 사용할 수 없다`() {
        // given
        val owner = userFixture.사용자()
        val coupon = billing.미사용_프로_쿠폰(owner.requiredId)
        jdbc.update("UPDATE pro_coupons SET disabled_at = now(), version = version + 1 WHERE id = ?", coupon.requiredId)

        // when & then
        assertThatThrownBy { target.create(owner.requiredId, CreatePersonalGalleryRequest(title = "프로 갤러리", planId = "pro", couponId = coupon.requiredId)) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_DISABLED)
        assertThat(galleries.count()).isZero()
        assertThat(benefits.getMyBenefits(owner.requiredId).freePlanAvailable).isTrue()
    }
}
