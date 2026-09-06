package com.soma.wes.billing.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.billing.dto.request.CheckoutRequest
import com.soma.wes.billing.dto.response.CheckoutResponse
import com.soma.wes.billing.dto.response.PlansResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Payment", description = "로컬 테스트 플랜 및 결제. 실결제는 제공하지 않는다.")
interface BillingControllerDocs {
    @Operation(summary = "플랜 목록", description = "설정 가능한 테스트 플랜과 테스트 결제 활성 여부를 반환한다.")
    fun plans(): ResponseEntity<PlansResponse>
    @Operation(summary = "테스트 결제", description = "local/test 프로필에서 명시적으로 활성화한 경우만 완료된다. 실제 금전 결제는 발생하지 않는다.")
    fun checkout(loginUser: LoginUser, request: CheckoutRequest): ResponseEntity<CheckoutResponse>
    @Operation(summary = "내 테스트 결제 조회")
    fun getCheckout(loginUser: LoginUser, checkoutId: String): ResponseEntity<CheckoutResponse>
}
