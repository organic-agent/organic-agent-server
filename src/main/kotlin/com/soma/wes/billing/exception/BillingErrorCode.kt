package com.soma.wes.billing.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class BillingErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    CHECKOUT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_503_1", "실결제는 지원하지 않습니다. 로컬 테스트 결제를 활성화해 주세요."),
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "BILLING_404_1", "사용 가능한 플랜이 아닙니다."),
    CHECKOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "BILLING_404_2", "결제 내역을 찾을 수 없습니다."),
    CHECKOUT_ALREADY_USED(HttpStatus.CONFLICT, "BILLING_409_1", "이미 갤러리 개설에 사용한 결제입니다."),
    CHECKOUT_EXPIRED(HttpStatus.GONE, "BILLING_410_1", "플랜 이용 기간이 지났습니다."),
    INVALID_PLAN_CONFIGURATION(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_503_2", "플랜 설정을 확인해 주세요."),
}
