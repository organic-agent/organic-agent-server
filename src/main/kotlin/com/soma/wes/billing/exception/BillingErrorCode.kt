package com.soma.wes.billing.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class BillingErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    CHECKOUT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_503_1", "카드 결제는 준비 중입니다. 프로 쿠폰을 이용해 주세요."),
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "BILLING_404_1", "사용 가능한 플랜이 아닙니다."),
    CHECKOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "BILLING_404_2", "결제 내역을 찾을 수 없습니다."),
    CHECKOUT_ALREADY_USED(HttpStatus.CONFLICT, "BILLING_409_1", "이미 갤러리 개설에 사용한 결제입니다."),
    CHECKOUT_EXPIRED(HttpStatus.GONE, "BILLING_410_1", "플랜 이용 기간이 지났습니다."),
    INVALID_PLAN_CONFIGURATION(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_503_2", "플랜 설정을 확인해 주세요."),
    INVALID_PLAN_REQUEST(HttpStatus.BAD_REQUEST, "BILLING_400_1", "요금제와 이용권을 올바르게 선택해 주세요."),
    PRO_COUPON_REQUIRED(HttpStatus.BAD_REQUEST, "BILLING_400_2", "프로 갤러리를 만들려면 미사용 프로 쿠폰이 필요합니다."),
    INVALID_COUPON_CODE(HttpStatus.BAD_REQUEST, "BILLING_400_3", "쿠폰 코드 형식이 올바르지 않습니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "BILLING_404_3", "사용 가능한 프로 쿠폰을 찾을 수 없습니다."),
    FREE_PLAN_ALREADY_USED(HttpStatus.CONFLICT, "BILLING_409_2", "무료 갤러리는 계정당 한 번만 만들 수 있습니다."),
    COUPON_CODE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "BILLING_409_3", "이미 등록된 쿠폰 코드입니다."),
    COUPON_DISABLED(HttpStatus.FORBIDDEN, "BILLING_403_1", "비활성화된 프로 쿠폰입니다. 관리자에게 문의해 주세요."),
    COUPON_ALREADY_USED(HttpStatus.CONFLICT, "BILLING_409_4", "이미 갤러리 개설에 사용한 프로 쿠폰입니다."),
}
