package com.soma.wes.admin.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.domain.AdminAuthEvent
import com.soma.wes.admin.domain.AdminProCouponStatus
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.dto.request.ChangeProCouponStatusRequest
import com.soma.wes.admin.dto.response.AdminProCouponResponse
import com.soma.wes.admin.dto.response.IssueProCouponResponse
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.repository.AdminProCouponRepository
import com.soma.wes.billing.domain.ProCoupon
import com.soma.wes.billing.repository.ProCouponRepository
import com.soma.wes.billing.support.CouponCodes
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Service
class AdminCouponService(
    private val accounts: AdminAccountRepository,
    private val coupons: ProCouponRepository,
    private val queryRepository: AdminProCouponRepository,
    private val codes: CouponCodes,
    private val audit: AdminAuditService,
    private val clock: Clock,
) {
    /** 관리자가 코드 한 개를 발급한다. 원문은 이 응답에서만 제공하고 감사 로그에도 남기지 않는다. */
    @Transactional
    fun issue(actorAdminId: Long, request: AdminReasonRequest): IssueProCouponResponse {
        requireActiveAdmin(actorAdminId)
        validateReason(request.reason)

        val code = codes.generate()
        val coupon = coupons.save(ProCoupon.of(
            codeHash = codes.hash(code), codeSuffix = code.takeLast(ProCoupon.CODE_SUFFIX_LENGTH),
            issuedByAdminId = actorAdminId,
        ))
        audit.recordEvent(
            action = AdminAuditAction.COUPON_CODE_ISSUED, outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actorAdminId, targetType = AdminAuditTargetType.PRO_COUPON,
            targetId = coupon.requiredId.toString(), targetLabel = null, reason = request.reason, sourceAddress = null,
        )
        return IssueProCouponResponse(couponId = coupon.requiredId, code = code)
    }

    /** 만료는 조회 시각으로 계산한다. 검색·페이지 크기를 제한해 전체 코드와 사용자 데이터를 노출하지 않는다. */
    @Transactional(readOnly = true)
    fun getCoupons(actorAdminId: Long, status: AdminProCouponStatus?, query: String?, page: Int, size: Int): PageResponse<AdminProCouponResponse> {
        requireActiveAdmin(actorAdminId)
        val normalized = query.orEmpty().trim()
        if (normalized.length > MAX_QUERY_LENGTH) throw AdminException(AdminErrorCode.INVALID_COUPON_FILTER)

        val found = queryRepository.search(
            status = status, query = normalized, at = ZonedDateTime.now(clock), pageable = PageRequests.of(page, size),
        )
        return PageResponse.of(found = found, contents = found.content.map(AdminProCouponResponse::from))
    }

    @Transactional(readOnly = true)
    fun getCoupon(couponId: Long, actorAdminId: Long): AdminProCouponResponse {
        requireActiveAdmin(actorAdminId)
        val row = queryRepository.find(id = couponId, at = ZonedDateTime.now(clock))
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        return AdminProCouponResponse.from(row)
    }

    /** 사용자 등록·쿠폰 소비와 같은 행 잠금 및 expectedVersion으로 오래된 화면의 상태 변경을 거절한다. */
    @Transactional
    fun changeStatus(couponId: Long, actorAdminId: Long, request: ChangeProCouponStatusRequest): AdminProCouponResponse {
        requireActiveAdmin(actorAdminId)
        validateReason(request.reason)
        val coupon = coupons.findWithLockById(couponId) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        if (coupon.version != request.expectedVersion) throw AdminException(AdminErrorCode.RESOURCE_VERSION_CONFLICT)
        if (coupon.consumedAt != null) throw AdminException(AdminErrorCode.COUPON_STATE_CHANGE_FORBIDDEN)
        if (request.enabled == (coupon.disabledAt == null)) throw AdminException(AdminErrorCode.COUPON_STATE_UNCHANGED)

        val now = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        coupon.changeEnabled(enabled = request.enabled, at = now)
        coupons.saveAndFlush(coupon)
        audit.recordEvent(
            action = if (request.enabled) AdminAuditAction.COUPON_CODE_ENABLED else AdminAuditAction.COUPON_CODE_DISABLED,
            outcome = AdminAuditOutcome.SUCCESS, actorAdminId = actorAdminId, targetType = AdminAuditTargetType.PRO_COUPON,
            targetId = coupon.requiredId.toString(), targetLabel = null, reason = request.reason, sourceAddress = null,
            changedFields = listOf("disabledAt"),
        )
        val row = queryRepository.find(id = couponId, at = now)
            ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        return AdminProCouponResponse.from(row)
    }

    private fun requireActiveAdmin(actorAdminId: Long) {
        val admin = accounts.findByIdOrNull(actorAdminId) ?: throw AdminException(AdminErrorCode.ACCOUNT_NOT_FOUND)
        if (admin.isSuspended()) throw AdminException(AdminErrorCode.ACCOUNT_SUSPENDED)
    }

    private fun validateReason(reason: String) {
        if (reason.isBlank() || reason.length > AdminAuthEvent.REASON_MAX_LENGTH) throw AdminException(AdminErrorCode.INVALID_REASON)
    }

    companion object {
        /** 화면의 식별자·이름 검색에 필요한 입력 상한. */
        private const val MAX_QUERY_LENGTH = 80
    }
}
