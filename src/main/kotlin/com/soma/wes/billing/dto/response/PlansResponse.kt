package com.soma.wes.billing.dto.response

import com.soma.wes.billing.domain.GalleryPlan
import io.swagger.v3.oas.annotations.media.Schema

data class PlansResponse(
    @field:Schema(description = "현재 프로 이용권 발급 방식", example = "COUPON")
    val mode: String = "COUPON",
    @field:Schema(description = "이전 테스트 결제의 호환 필드. 테스트 이용권 발급은 종료되어 항상 false", example = "false")
    val testCheckoutEnabled: Boolean = false,
    @field:Schema(description = "카드 결제는 아직 준비 중", example = "COMING_SOON")
    val cardPaymentStatus: String = "COMING_SOON",
    @field:Schema(description = "갤러리별로 적용하는 무료·프로 요금제")
    val plans: List<PlanResponse> = GalleryPlan.entries.map(PlanResponse::from),
)

data class PlanResponse(
    @field:Schema(description = "갤러리 개설 요청의 planId", example = "free")
    val id: String,
    @field:Schema(description = "요금제 표시 이름", example = "무료")
    val name: String,
    @field:Schema(description = "무료는 0, 가격 미정인 프로는 null. 카드 결제는 준비 중", example = "0")
    val amount: Long?,
    @field:Schema(description = "가격 표시 통화", example = "KRW")
    val currency: String,
    @field:Schema(description = "무료·프로 모두 달력 기준이므로 null")
    val durationDays: Long?,
    @field:Schema(description = "생성일부터 달력 기준 이용 기간. 무료는 1개월, 프로는 12개월(1년)", example = "12")
    val durationMonths: Long?,
    @field:Schema(description = "갤러리에서 삭제되지 않은 사진의 최대 장수. 업로드 대기도 예약 장수로 포함한다.", example = "500")
    val maxPhotoCount: Int,
    @field:Schema(description = "계정당 한 번 생성할 수 있는 무료이면 true", example = "true")
    val oncePerAccount: Boolean,
    @field:Schema(description = "프로 개설에 미사용 쿠폰이 필요한지 여부", example = "false")
    val couponRequired: Boolean,
) {
    companion object {
        fun from(plan: GalleryPlan): PlanResponse = PlanResponse(
            id = plan.planId,
            name = plan.displayName,
            amount = if (plan == GalleryPlan.FREE) 0 else null,
            currency = "KRW",
            durationDays = null,
            durationMonths = when (plan) {
                GalleryPlan.FREE -> GalleryPlan.FREE_DURATION_MONTHS
                GalleryPlan.PRO -> GalleryPlan.PRO_DURATION_MONTHS
            },
            maxPhotoCount = plan.maxPhotoCount,
            oncePerAccount = plan == GalleryPlan.FREE,
            couponRequired = plan == GalleryPlan.PRO,
        )
    }
}
