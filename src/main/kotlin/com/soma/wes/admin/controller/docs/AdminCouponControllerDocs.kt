package com.soma.wes.admin.controller.docs

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.domain.AdminProCouponStatus
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.dto.request.ChangeProCouponStatusRequest
import com.soma.wes.admin.dto.response.AdminProCouponResponse
import com.soma.wes.admin.dto.response.IssueProCouponResponse
import com.soma.wes.global.page.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Admin Coupon", description = "프로 쿠폰 코드 발급과 상태 관리")
interface AdminCouponControllerDocs {
    @Operation(summary = "일회용 프로 코드 발급", description = "활성 관리자만 한 개를 발급한다. 원문은 발급 응답에만 반환하며 DB에는 해시와 식별용 끝 8자리만 저장한다. 코드당 한 계정이 등록하고, 180일은 새 갤러리에 사용할 때 시작한다.")
    fun issue(loginUser: AdminLoginUser, request: AdminReasonRequest): ResponseEntity<IssueProCouponResponse>

    @Operation(summary = "발급 코드 목록·상태 검색", description = "코드 id·끝 8자리·등록 사용자 id·이름으로 검색한다. 최신 발급 순으로 페이지를 반환하며 원문·해시는 제외한다. 미사용 코드에는 만료일이 없다.")
    fun getCoupons(loginUser: AdminLoginUser, status: AdminProCouponStatus?, query: String?, page: Int, size: Int): ResponseEntity<PageResponse<AdminProCouponResponse>>

    @Operation(summary = "쿠폰 관리 정보 조회", description = "현재 상태·버전·등록 계정·연결 갤러리·180일 만료 시각을 조회한다.")
    fun getCoupon(loginUser: AdminLoginUser, couponId: Long): ResponseEntity<AdminProCouponResponse>

    @Operation(summary = "미사용 코드 비활성화·재활성화", description = "비활성 코드는 등록과 갤러리 생성에 사용할 수 없다. 이미 사용한 쿠폰은 변경하지 않는다. 사유와 expectedVersion을 요구하며, 등록·사용·다른 관리자 변경이 먼저 일어나면 409로 거절한다.")
    fun changeStatus(loginUser: AdminLoginUser, couponId: Long, request: ChangeProCouponStatusRequest): ResponseEntity<AdminProCouponResponse>
}
