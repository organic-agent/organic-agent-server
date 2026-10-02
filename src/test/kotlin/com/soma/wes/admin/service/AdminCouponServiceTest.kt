package com.soma.wes.admin.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.support.CouponCodes
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import com.soma.wes.admin.domain.AdminProCouponStatus
import com.soma.wes.admin.dto.request.ChangeProCouponStatusRequest
import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.admin.fixture.AdminResourceFixture
import org.springframework.context.annotation.Import
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
@Import(BillingFixture::class)
class AdminCouponServiceTest @Autowired constructor(
    private val target: AdminCouponService,
    private val admins: AdminAccountFixture,
    private val accounts: AdminAccountRepository,
    private val coupons: ProCouponRepository,
    private val codes: CouponCodes,
    private val audits: AdminAuditLogRepository,
    private val billing: BillingFixture,
    private val resources: AdminResourceFixture,
    private val clock: Clock,
) {
    @Test
    fun `원문은 반환만 하고 DB에 해시와 발급 감사만 저장한다`() {
        // given
        val admin = admins.관리자("coupon-issuer")

        // when
        val result = target.issue(admin.requiredId, AdminReasonRequest("테스트 프로 쿠폰 발급"))
        val stored = coupons.findById(result.couponId).orElseThrow()
        val audit = audits.findAll().single()

        // then
        assertSoftly { softly ->
            softly.assertThat(result.code).matches("WES-[0-9A-F]{32}")
            softly.assertThat(stored.codeHash).isEqualTo(codes.hash(result.code))
            softly.assertThat(stored.registeredAt).isNull()
            softly.assertThat(audit.action).isEqualTo(AdminAuditAction.COUPON_CODE_ISSUED)
            softly.assertThat(audit.targetType).isEqualTo(AdminAuditTargetType.PRO_COUPON)
            softly.assertThat(audit.targetId).isEqualTo(result.couponId.toString())
            softly.assertThat(audit.reason).doesNotContain(result.code, stored.codeHash)
        }
    }

    @Test
    fun `정지된 관리자나 존재하지 않는 관리자는 발급하지 못한다`() {
        // given
        val admin = admins.관리자("suspended-coupon-issuer")
        admin.suspend()
        accounts.saveAndFlush(admin)

        // when & then
        assertThatThrownBy { target.issue(admin.requiredId, AdminReasonRequest("발급")) }
            .isInstanceOf(AdminException::class.java)
            .extracting("errorCode").isEqualTo(AdminErrorCode.ACCOUNT_SUSPENDED)
        assertThatThrownBy { target.issue(Long.MAX_VALUE, AdminReasonRequest("발급")) }
            .isInstanceOf(AdminException::class.java)
            .extracting("errorCode").isEqualTo(AdminErrorCode.ACCOUNT_NOT_FOUND)
        assertThat(coupons.count()).isZero()
    }

    @Test
    fun `발급 사유가 잘못되면 코드와 감사 로그가 남지 않는다`() {
        // given
        val admin = admins.관리자("invalid-coupon-issuer")

        // when & then
        for (reason in listOf("", "x".repeat(501))) {
            assertThatThrownBy { target.issue(admin.requiredId, AdminReasonRequest(reason)) }
                .isInstanceOf(AdminException::class.java)
                .extracting("errorCode").isEqualTo(AdminErrorCode.INVALID_REASON)
        }
        assertThat(coupons.count()).isZero()
        assertThat(audits.count()).isZero()
    }

    @Nested
    @DisplayName("발급 코드 목록을 조회할 때")
    inner class Query {
        @Test
        fun `끝자리와 발급 관리자만 반환하며 코드 원문과 해시는 목록에 없다`() {
            // given
            val admin = admins.관리자("coupon-list")
            val issued = target.issue(admin.requiredId, AdminReasonRequest("[TEST_OPERATION] 목록 검증"))
            val stored = coupons.findById(issued.couponId).orElseThrow()

            // when
            val result = target.getCoupons(admin.requiredId, null, null, 0, 25)
            val detail = target.getCoupon(issued.couponId, admin.requiredId)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.totalCount).isEqualTo(1L)
                softly.assertThat(detail.status).isEqualTo(AdminProCouponStatus.ISSUED)
                softly.assertThat(detail.codeSuffix).isEqualTo(issued.code.takeLast(8))
                softly.assertThat(detail.issuedByAdminId).isEqualTo(admin.requiredId)
                softly.assertThat(result.toString()).doesNotContain(issued.code, stored.codeHash)
            }
        }

        @Test
        fun `등록 사용 만료 비활성 상태를 구분하고 계정과 갤러리를 함께 조회한다`() {
            // given
            val admin = admins.관리자("coupon-states")
            val owner = resources.사용자(admin.requiredId)
            val registered = billing.미사용_프로_쿠폰(owner.id)
            val now = ZonedDateTime.now(clock)
            val gallery = resources.갤러리(admin.requiredId, owner.id)
            val used = billing.미사용_프로_쿠폰(owner.id)
            used.consume(gallery.id, now, now.plusYears(1))
            coupons.saveAndFlush(used)
            val expired = billing.미사용_프로_쿠폰(owner.id, registeredAt = now.minusDays(200))
            expired.consume(gallery.id, now.minusDays(181), now.minusDays(1))
            coupons.saveAndFlush(expired)
            val disabled = target.issue(admin.requiredId, AdminReasonRequest("발급"))
            target.changeStatus(disabled.couponId, admin.requiredId, ChangeProCouponStatusRequest(false, 0, "차단"))

            // when
            val result = target.getCoupons(admin.requiredId, null, null, 0, 25)
            val byId = result.contents.associateBy { it.couponId }

            // then
            assertSoftly { softly ->
                softly.assertThat(byId.getValue(registered.requiredId).status).isEqualTo(AdminProCouponStatus.REGISTERED)
                softly.assertThat(byId.getValue(registered.requiredId).userNickname).isEqualTo(owner.label)
                softly.assertThat(byId.getValue(used.requiredId).status).isEqualTo(AdminProCouponStatus.USED)
                softly.assertThat(byId.getValue(used.requiredId).galleryTitle).isEqualTo("쿠폰 갤러리")
                softly.assertThat(byId.getValue(expired.requiredId).status).isEqualTo(AdminProCouponStatus.EXPIRED)
                softly.assertThat(byId.getValue(disabled.couponId).status).isEqualTo(AdminProCouponStatus.DISABLED)
            }
            assertThat(target.getCoupons(admin.requiredId, AdminProCouponStatus.EXPIRED, null, 0, 25).contents.map { it.couponId })
                .containsExactly(expired.requiredId)
        }

        @Test
        fun `상태와 식별자 검색을 적용하고 페이지로 나눈다`() {
            // given
            val admin = admins.관리자("coupon-search")
            val issued = (1..3).map { target.issue(admin.requiredId, AdminReasonRequest("발급")) }

            // when
            val first = target.getCoupons(admin.requiredId, null, "", 0, 2)
            val second = target.getCoupons(admin.requiredId, null, "", 1, 2)
            val searched = target.getCoupons(admin.requiredId, AdminProCouponStatus.ISSUED, issued[0].code.takeLast(8).lowercase(), 0, 25)

            // then
            assertSoftly { softly ->
                softly.assertThat(first.totalCount).isEqualTo(3L)
                softly.assertThat(first.hasNext).isTrue()
                softly.assertThat(second.hasNext).isFalse()
                softly.assertThat(first.contents.map { it.couponId }).containsExactly(issued[2].couponId, issued[1].couponId)
                softly.assertThat(second.contents.map { it.couponId }).containsExactly(issued[0].couponId)
                softly.assertThat(searched.contents.map { it.couponId }).containsExactly(issued[0].couponId)
                softly.assertThat(target.getCoupons(admin.requiredId, null, "%", 0, 25).totalCount).isZero()
            }
        }

        @Test
        fun `잘못된 검색어 없는 쿠폰과 정지된 관리자를 거절한다`() {
            // given
            val admin = admins.관리자("coupon-query-denied")

            // when & then
            assertThatThrownBy { target.getCoupons(admin.requiredId, null, "x".repeat(81), 0, 25) }
                .isInstanceOf(AdminException::class.java).extracting("errorCode").isEqualTo(AdminErrorCode.INVALID_COUPON_FILTER)
            assertThatThrownBy { target.getCoupon(Long.MAX_VALUE, admin.requiredId) }
                .isInstanceOf(AdminException::class.java).extracting("errorCode").isEqualTo(AdminErrorCode.RESOURCE_NOT_FOUND)
            admin.suspend()
            accounts.saveAndFlush(admin)
            assertThatThrownBy { target.getCoupons(admin.requiredId, null, null, 0, 25) }
                .isInstanceOf(AdminException::class.java).extracting("errorCode").isEqualTo(AdminErrorCode.ACCOUNT_SUSPENDED)
        }
    }

    @Nested
    @DisplayName("미사용 코드 상태를 변경할 때")
    inner class Status {
        @Test
        fun `등록된 쿠폰을 비활성화하고 등록 이력 그대로 재활성화한다`() {
            // given
            val admin = admins.관리자("coupon-toggle")
            val owner = resources.사용자(admin.requiredId)
            val coupon = billing.미사용_프로_쿠폰(owner.id)
            val before = target.getCoupon(coupon.requiredId, admin.requiredId)

            // when
            val disabled = target.changeStatus(coupon.requiredId, admin.requiredId, ChangeProCouponStatusRequest(false, before.version, "[POLICY_ENFORCEMENT] 차단"))
            val enabled = target.changeStatus(coupon.requiredId, admin.requiredId, ChangeProCouponStatusRequest(true, disabled.version, "[CUSTOMER_REQUEST] 재활성화"))

            // then
            assertSoftly { softly ->
                softly.assertThat(disabled.status).isEqualTo(AdminProCouponStatus.DISABLED)
                softly.assertThat(disabled.disabledAt).isNotNull()
                softly.assertThat(enabled.status).isEqualTo(AdminProCouponStatus.REGISTERED)
                softly.assertThat(enabled.disabledAt).isNull()
                softly.assertThat(enabled.registeredAt).isEqualTo(before.registeredAt)
                softly.assertThat(enabled.userId).isEqualTo(owner.id)
                softly.assertThat(enabled.expiresAt).isNull()
                softly.assertThat(enabled.version).isEqualTo(before.version + 2)
            }
            assertThat(audits.findAll().filter { it.targetType == AdminAuditTargetType.PRO_COUPON }.map { it.action }).containsExactly(AdminAuditAction.COUPON_CODE_DISABLED, AdminAuditAction.COUPON_CODE_ENABLED)
        }

        @Test
        fun `등록이나 다른 관리자 변경 이후의 오래된 화면은 최신 상태를 덮지 못한다`() {
            // given
            val admin = admins.관리자("coupon-stale")
            val issued = target.issue(admin.requiredId, AdminReasonRequest("발급"))
            val coupon = coupons.findById(issued.couponId).orElseThrow()
            coupon.register(resources.사용자(admin.requiredId).id, ZonedDateTime.now(clock))
            coupons.saveAndFlush(coupon)

            // when & then
            assertThatThrownBy { target.changeStatus(issued.couponId, admin.requiredId, ChangeProCouponStatusRequest(false, 0, "차단")) }
                .isInstanceOf(AdminException::class.java).extracting("errorCode").isEqualTo(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
            assertThat(target.getCoupon(issued.couponId, admin.requiredId).status).isEqualTo(AdminProCouponStatus.REGISTERED)
        }

        @Test
        fun `이미 사용한 쿠폰은 회수할 수 없고 갤러리 이용 기간을 유지한다`() {
            // given
            val admin = admins.관리자("coupon-used")
            val owner = resources.사용자(admin.requiredId)
            val coupon = billing.미사용_프로_쿠폰(owner.id)
            val now = ZonedDateTime.now(clock)
            coupon.consume(resources.갤러리(admin.requiredId, owner.id).id, now, now.plusYears(1))
            coupons.saveAndFlush(coupon)
            val before = target.getCoupon(coupon.requiredId, admin.requiredId)

            // when & then
            assertThatThrownBy { target.changeStatus(coupon.requiredId, admin.requiredId, ChangeProCouponStatusRequest(false, before.version, "회수")) }
                .isInstanceOf(AdminException::class.java).extracting("errorCode").isEqualTo(AdminErrorCode.COUPON_STATE_CHANGE_FORBIDDEN)
            val after = target.getCoupon(coupon.requiredId, admin.requiredId)
            assertThat(after).isEqualTo(before)
            assertThat(audits.findAll().filter { it.targetType == AdminAuditTargetType.PRO_COUPON }).isEmpty()
        }

        @Test
        fun `같은 상태와 잘못된 사유는 변경하지 않는다`() {
            // given
            val admin = admins.관리자("coupon-invalid-change")
            val issued = target.issue(admin.requiredId, AdminReasonRequest("발급"))

            // when & then
            assertThatThrownBy { target.changeStatus(issued.couponId, admin.requiredId, ChangeProCouponStatusRequest(true, 0, "재활성")) }
                .isInstanceOf(AdminException::class.java).extracting("errorCode").isEqualTo(AdminErrorCode.COUPON_STATE_UNCHANGED)
            assertThatThrownBy { target.changeStatus(issued.couponId, admin.requiredId, ChangeProCouponStatusRequest(false, 0, "")) }
                .isInstanceOf(AdminException::class.java).extracting("errorCode").isEqualTo(AdminErrorCode.INVALID_REASON)
            assertThat(target.getCoupon(issued.couponId, admin.requiredId).version).isZero()
            assertThat(audits.count()).isEqualTo(1L)
        }

        @Test
        fun `같은 버전으로 동시에 상태를 바꾸면 한 요청만 성공한다`() {
            // given
            val admin = admins.관리자("coupon-concurrent")
            val issued = target.issue(admin.requiredId, AdminReasonRequest("발급"))
            val start = CountDownLatch(1)

            // when
            val results = Executors.newFixedThreadPool(2).use { executor ->
                val futures = (1..2).map { executor.submit<Result<Long>> {
                    start.await()
                    runCatching { target.changeStatus(issued.couponId, admin.requiredId, ChangeProCouponStatusRequest(false, 0, "차단")).version }
                } }
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            }

            // then
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(checkNotNull(results.single { it.isFailure }.exceptionOrNull())).isInstanceOf(AdminException::class.java)
                .extracting("errorCode").isEqualTo(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
            assertThat(target.getCoupon(issued.couponId, admin.requiredId).status).isEqualTo(AdminProCouponStatus.DISABLED)
        }
    }
}
