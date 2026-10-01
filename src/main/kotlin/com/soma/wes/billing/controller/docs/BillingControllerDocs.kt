package com.soma.wes.billing.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.billing.dto.request.CheckoutRequest
import com.soma.wes.billing.dto.response.CheckoutResponse
import com.soma.wes.billing.dto.response.PlansResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Payment", description = "무료·프로 요금제. 카드 결제는 준비 중이며 프로는 쿠폰으로 이용한다.")
interface BillingControllerDocs {
    @Operation(summary = "요금제 목록", description = "무료는 계정당 한 번, 갤러리 생성일부터 달력 기준 한 달·500장이다. 프로는 갤러리 생성일부터 180일·10,000장이다. 카드 결제는 COMING_SOON이고 프로 쿠폰만 사용할 수 있다. 사진을 삭제하면 해당 갤러리의 업로드 가능 장수를 돌려준다.")
    fun plans(): ResponseEntity<PlansResponse>
    @Operation(summary = "결제 준비 중", description = "카드 결제는 아직 제공하지 않으며 항상 503으로 거절한다. 이전 테스트 결제 설정으로 활성화할 수 없다.")
    fun checkout(loginUser: LoginUser, request: CheckoutRequest): ResponseEntity<CheckoutResponse>
    @Operation(summary = "내 테스트 결제 조회")
    fun getCheckout(loginUser: LoginUser, checkoutId: String): ResponseEntity<CheckoutResponse>
}
