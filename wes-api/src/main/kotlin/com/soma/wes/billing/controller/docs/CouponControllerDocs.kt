package com.soma.wes.billing.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.billing.dto.request.RegisterProCouponRequest
import com.soma.wes.billing.dto.request.ResolveProCouponRequest
import com.soma.wes.billing.dto.response.MyBenefitsResponse
import com.soma.wes.billing.dto.response.ProCouponLinkResponse
import com.soma.wes.billing.dto.response.ProCouponResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Coupon", description = "프로 쿠폰 등록과 내 이용권")
interface CouponControllerDocs {
    @Operation(summary = "프로 쿠폰 링크 조회", description = "코드를 등록하거나 소비하지 않고 링크의 이동 목적지를 조회한다. 사용한 쿠폰은 갤러리 상세 조회 권한이 있을 때만 galleryId를 반환한다. 미사용·권한 없음·갤러리 삭제이면 galleryId는 null이며 등록 화면을 유지한다. 없는 코드는 404로 거절한다. 코드를 URL과 접근 로그에 남기지 않도록 요청 본문으로 받는다.")
    fun resolve(loginUser: LoginUser, request: ResolveProCouponRequest): ResponseEntity<ProCouponLinkResponse>

    @Operation(summary = "내 무료 이용 가능 여부·프로 쿠폰", description = "무료는 계정당 한 번이며 갤러리 삭제·만료 후에도 다시 제공하지 않는다. 쿠폰은 본인이 등록한 것만 반환한다.")
    fun getMyBenefits(loginUser: LoginUser): ResponseEntity<MyBenefitsResponse>

    @Operation(summary = "프로 쿠폰 코드 등록", description = "선물 링크로 전달한 관리자 발급 코드를 로그인한 계정에 등록한다. 같은 계정의 재요청은 기존 쿠폰을 반환하며 다른 계정의 중복·동시 등록은 409로 거절한다. 등록 후 미사용 쿠폰 한 장이 보관되며, 달력 기준 1년은 새 갤러리 생성에 사용할 때 시작한다. 기존 무료 갤러리 업그레이드는 지원하지 않는다.")
    fun register(loginUser: LoginUser, request: RegisterProCouponRequest): ResponseEntity<ProCouponResponse>
}
