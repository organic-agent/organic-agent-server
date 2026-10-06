package com.soma.wes.billing.service

import com.soma.wes.billing.dto.request.RegisterProCouponRequest
import com.soma.wes.billing.dto.request.ResolveProCouponRequest
import com.soma.wes.billing.dto.response.ProCouponLinkResponse
import com.soma.wes.billing.dto.response.ProCouponResponse
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.support.CouponCodes
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.service.PersonalGalleryService
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
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
    private val galleryAccess: GalleryAccessPolicy,
    private val galleryFixture: GalleryFixture,
    private val workspaces: WorkspaceRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
) {
    @Nested
    @DisplayName("쿠폰 링크를 조회할 때")
    inner class Resolve {
        @Test
        fun `미등록 코드는 계정에 등록하거나 기간을 시작하지 않고 등록 화면에 남긴다`() {
            // given
            val user = users.사용자()
            val issued = billing.미등록_프로_코드()

            // when
            val result = target.resolve(user.requiredId, ResolveProCouponRequest("  ${issued.code.lowercase()}  "))

            // then
            val saved = coupons.findById(issued.id).orElseThrow()
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(ProCouponLinkResponse.Status.AVAILABLE)
                softly.assertThat(result.galleryId).isNull()
                softly.assertThat(saved.userId).isNull()
                softly.assertThat(saved.registeredAt).isNull()
                softly.assertThat(saved.consumedAt).isNull()
                softly.assertThat(saved.expiresAt).isNull()
            }
        }

        @Test
        fun `등록만 한 쿠폰은 조회해도 사용되지 않고 등록 시각도 바뀌지 않는다`() {
            // given
            val owner = users.사용자()
            val issued = billing.미등록_프로_코드()
            val registered = target.register(owner.requiredId, RegisterProCouponRequest(issued.code))

            // when
            val result = target.resolve(owner.requiredId, ResolveProCouponRequest(issued.code))

            // then
            val saved = coupons.findById(issued.id).orElseThrow()
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(ProCouponLinkResponse.Status.AVAILABLE)
                softly.assertThat(result.galleryId).isNull()
                softly.assertThat(saved.registeredAt?.toInstant()).isEqualTo(registered.registeredAt.toInstant())
                softly.assertThat(saved.consumedAt).isNull()
            }
        }

        @Test
        fun `사용한 쿠폰은 해당 갤러리로 연결하고 만료일을 연장하지 않는다`() {
            // given
            val owner = users.사용자()
            val issued = billing.미등록_프로_코드()
            target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
            val gallery = galleries.create(
                userId = owner.requiredId,
                request = CreatePersonalGalleryRequest(title = "링크 갤러리", planId = "pro", couponId = issued.id),
            )

            // when
            val result = target.resolve(owner.requiredId, ResolveProCouponRequest(issued.code))

            // then
            val saved = coupons.findById(issued.id).orElseThrow()
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(ProCouponLinkResponse.Status.USED)
                softly.assertThat(result.galleryId).isEqualTo(gallery.id)
                softly.assertThat(saved.expiresAt?.toInstant()).isEqualTo(gallery.planExpiresAt?.toInstant())
            }
        }

        @Test
        fun `다른 계정의 쿠폰과 등록 계정이 삭제된 쿠폰은 갤러리 정보를 반환하지 않는다`() {
            // given
            val owner = users.사용자()
            val other = users.사용자()
            val issued = billing.미등록_프로_코드()
            target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
            val gallery = galleries.create(
                userId = owner.requiredId,
                request = CreatePersonalGalleryRequest(title = "소유자 갤러리", planId = "pro", couponId = issued.id),
            )

            // when & then
            assertThat(target.resolve(other.requiredId, ResolveProCouponRequest(issued.code)).galleryId).isNull()
            assertThatThrownBy { galleryAccess.requireMetadataViewer(gallery.id, other.requiredId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            assertThatThrownBy { target.register(other.requiredId, RegisterProCouponRequest(issued.code)) }
                .isInstanceOf(BillingException::class.java)
                .hasMessage("이미 등록된 쿠폰 코드입니다.")
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_CODE_ALREADY_REGISTERED)
            jdbc.update("DELETE FROM users WHERE id = ?", owner.requiredId)
            assertThat(target.resolve(other.requiredId, ResolveProCouponRequest(issued.code)).galleryId).isNull()
        }

        @Test
        fun `쿠폰 소유자가 아니어도 갤러리 파트너와 초대 멤버는 연결하고 탈퇴 후에는 연결하지 않는다`() {
            // given
            val owner = users.사용자()
            val partner = users.사용자()
            val issued = billing.미등록_프로_코드()
            target.register(owner.requiredId, RegisterProCouponRequest(issued.code))
            val gallery = galleries.create(
                userId = owner.requiredId,
                request = CreatePersonalGalleryRequest(title = "함께 고르는 갤러리", planId = "pro", couponId = issued.id),
            )
            val workspace = workspaces.findByPersonalOwnerUserId(owner.requiredId)!!
            val membership = workspaceMembers.saveAndFlush(
                WorkspaceMember(workspaceId = workspace.requiredId, userId = partner.requiredId, role = WorkspaceRole.MEMBER),
            )
            val invited = galleryFixture.멤버(gallery.id)

            // when & then
            for (userId in listOf(partner.requiredId, invited.requiredId)) {
                assertThat(target.resolve(userId, ResolveProCouponRequest(issued.code)).galleryId).isEqualTo(gallery.id)
                assertThat(galleryAccess.requireMetadataViewer(gallery.id, userId).requiredId).isEqualTo(gallery.id)
            }
            membership.deletedAt = ZonedDateTime.now(clock)
            workspaceMembers.saveAndFlush(membership)
            assertThat(target.resolve(partner.requiredId, ResolveProCouponRequest(issued.code)).galleryId).isNull()
            assertThat(coupons.findById(issued.id).orElseThrow().userId).isEqualTo(owner.requiredId)
            jdbc.update("UPDATE workspace_members SET deleted_at = now() WHERE workspace_id = ? AND user_id = ?", workspace.requiredId, owner.requiredId)
            assertThat(target.resolve(owner.requiredId, ResolveProCouponRequest(issued.code)).galleryId).isNull()
        }

        @Test
        fun `비활성 쿠폰과 갤러리가 삭제된 사용 쿠폰은 재사용 가능한 상태로 바뀌지 않는다`() {
            // given
            val owner = users.사용자()
            val disabled = billing.미등록_프로_코드()
            val disabledCoupon = coupons.findById(disabled.id).orElseThrow()
            disabledCoupon.changeEnabled(false, ZonedDateTime.now(clock))
            coupons.saveAndFlush(disabledCoupon)
            val used = billing.미등록_프로_코드()
            target.register(owner.requiredId, RegisterProCouponRequest(used.code))
            val gallery = galleries.create(
                userId = owner.requiredId,
                request = CreatePersonalGalleryRequest(title = "삭제할 갤러리", planId = "pro", couponId = used.id),
            )
            jdbc.update("UPDATE galleries SET deleted_at = now() WHERE id = ?", gallery.id)
            assertThat(target.resolve(owner.requiredId, ResolveProCouponRequest(used.code)).galleryId).isNull()
            jdbc.update("DELETE FROM galleries WHERE id = ?", gallery.id)

            // when
            val disabledResult = target.resolve(owner.requiredId, ResolveProCouponRequest(disabled.code))
            val usedResult = target.resolve(owner.requiredId, ResolveProCouponRequest(used.code))

            // then
            assertSoftly { softly ->
                softly.assertThat(disabledResult.status).isEqualTo(ProCouponLinkResponse.Status.DISABLED)
                softly.assertThat(disabledResult.galleryId).isNull()
                softly.assertThat(usedResult.status).isEqualTo(ProCouponLinkResponse.Status.USED)
                softly.assertThat(usedResult.galleryId).isNull()
            }
        }

        @Test
        fun `없는 코드와 잘못된 형식은 조회하지 않는다`() {
            // given
            val user = users.사용자()

            // when & then
            assertThatThrownBy { target.resolve(user.requiredId, ResolveProCouponRequest(codes.generate())) }
                .isInstanceOf(BillingException::class.java)
                .extracting("errorCode").isEqualTo(BillingErrorCode.COUPON_NOT_FOUND)
            for (code in listOf("", "bad-code", "x".repeat(81))) {
                assertThatThrownBy { target.resolve(user.requiredId, ResolveProCouponRequest(code)) }
                    .isInstanceOf(BillingException::class.java)
                    .extracting("errorCode").isEqualTo(BillingErrorCode.INVALID_COUPON_CODE)
            }
        }
    }

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
