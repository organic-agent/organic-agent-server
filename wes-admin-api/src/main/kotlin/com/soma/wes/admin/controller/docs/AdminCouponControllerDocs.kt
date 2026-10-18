package com.soma.wes.admin.controller.docs

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.domain.AdminProCouponStatus
import com.soma.wes.admin.dto.request.IssueProCouponRequest
import com.soma.wes.admin.dto.response.AdminProCouponUseResponse
import com.soma.wes.admin.dto.request.ChangeProCouponStatusRequest
import com.soma.wes.admin.dto.response.AdminProCouponResponse
import com.soma.wes.admin.dto.response.IssueProCouponResponse
import com.soma.wes.global.page.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Admin Coupon", description = "프로 쿠폰 코드 발급과 상태 관리")
interface AdminCouponControllerDocs {
    @Operation(summary = "프로 쿠폰 발급", description = "코드를 직접 지정하거나 자동 생성한다. 1회용·공유 계정당 1회·공유 반복 사용과 사용 마감일을 선택한다. 자동 생성 코드 원문은 발급 때만 반환한다. Pro 이용기간은 각 갤러리 생성일부터 1년이다.")
    fun issue(loginUser: AdminLoginUser, request: IssueProCouponRequest): ResponseEntity<IssueProCouponResponse>

    @Operation(summary = "발급 코드 목록·상태 검색", description = "코드 id·끝 8자리·등록 사용자 id·이름으로 검색한다. 최신 발급 순으로 페이지를 반환하며 원문·해시는 제외한다. 쿠폰 사용 마감일과 Pro 만료일은 별개다. 직접 지정 코드도 검색한다.")
    fun getCoupons(loginUser: AdminLoginUser, status: AdminProCouponStatus?, query: String?, page: Int, size: Int): ResponseEntity<PageResponse<AdminProCouponResponse>>

    @Operation(summary = "쿠폰 관리 정보 조회", description = "현재 상태·버전·등록 계정·연결 갤러리·달력 기준 1년 만료 시각을 조회한다.")
    fun getCoupon(loginUser: AdminLoginUser, couponId: Long): ResponseEntity<AdminProCouponResponse>

    @Operation(summary = "쿠폰 사용 이력", description = "실제 갤러리 생성에 성공한 사용만 최신순으로 반환한다. 계정·갤러리를 삭제해도 사용 기록은 남는다.")
    fun getUses(loginUser: AdminLoginUser, couponId: Long, page: Int, size: Int): ResponseEntity<PageResponse<AdminProCouponUseResponse>>

    @Operation(summary = "코드 비활성화·재활성화", description = "비활성 코드는 등록과 갤러리 생성에 사용할 수 없다. 이미 사용한 1회용 쿠폰은 변경하지 않는다. 공유 코드는 사용 후에도 중지할 수 있으며 기존 갤러리에 영향을 주지 않는다. 사유와 expectedVersion을 요구하며, 등록·사용·다른 관리자 변경이 먼저 일어나면 409로 거절한다.")
    fun changeStatus(loginUser: AdminLoginUser, couponId: Long, request: ChangeProCouponStatusRequest): ResponseEntity<AdminProCouponResponse>
}
