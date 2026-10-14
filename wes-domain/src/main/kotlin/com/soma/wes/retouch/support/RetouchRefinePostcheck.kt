package com.soma.wes.retouch.support

import com.soma.wes.retouch.domain.RetouchRefineStatus
import com.soma.wes.retouch.dto.response.RefineRetouchResponse
import org.springframework.stereotype.Component

/**
 * 모델 출력을 작가에게 넘기기 전의 마지막 문.
 *
 * 프롬프트에 적은 규칙은 지켜지는 편이지 보장되지 않는다(05 자료의 τ-bench). 그래서 지키지 못하면 해로운 것만
 * 골라 서버가 다시 판정한다. 규칙별 실측(평가셋 94건 × 3회)은 `docs/plans/2026-09-12/retouch-refine-v2.md` §3의 표에 있다.
 */
@Component
class RetouchRefinePostcheck {

    fun apply(response: RefineRetouchResponse, originalText: String): RefineRetouchResponse {
        if (response.status == RetouchRefineStatus.NOT_A_REQUEST) {
            return response.copy(refinedText = null, question = "", options = emptyList(), items = emptyList())
        }

        val squashedText = squash(originalText)
        val grounded = response.copy(
            items = response.items.filter { squash(it.sourceSpan) in squashedText },
            options = response.options.filter { squash(it.sourceSpan) in squashedText },
        )
        val withoutUnaskedBody = grounded.copy(options = grounded.options.filter { bodyAllowed(response, originalText) || !mentionsBody(it.label) })

        if (response.pointMatchesText == false && response.status == RetouchRefineStatus.READY) {
            return withoutUnaskedBody.copy(status = RetouchRefineStatus.NEEDS_CLARIFICATION, refinedText = null)
        }
        return withoutUnaskedBody
    }

    /** 원문이 신체를 말했거나 탭한 것 자체가 신체면, 신체 선택지는 사진·원문에 근거가 있는 것이다. */
    private fun bodyAllowed(response: RefineRetouchResponse, originalText: String) =
        mentionsBody(originalText) || mentionsBody(response.tappedObject ?: "")

    private fun mentionsBody(text: String) = BODY_WORDS.any { it in text }

    private fun squash(text: String) = text.filterNot { it.isWhitespace() }

    companion object {

        /**
         * 신체·얼굴 어휘. 원문에도 탭한 대상에도 이것이 없는데 선택지가 신체 보정을 제안하면 버린다 —
         * 되묻기가 부부에게 외모 보정을 권하는 통로가 되지 않게 하는 규칙이다.
         *
         * 짧은 낱말(눈·목·배·살·점·코·키…)은 다른 말에 묻혀 오탐한다. 실측에서 '눈에 띄게'·'병목'·'배경'·
         * '살짝'·'지점'에 걸려 정상 선택지 85개를 버렸다. 그래서 다른 말에 잘 묻히지 않는 형태만 남겼다.
         * 정확한 판정은 선택지가 `region`을 함께 내야 가능하고, 그건 프롬프트 개정(연구 쪽)이 먼저다.
         */
        private val BODY_WORDS = listOf(
            "얼굴", "이목구비", "눈썹", "눈가", "눈밑", "눈 밑", "쌍꺼풀", "콧볼", "코끝", "입술", "입꼬리",
            "치아", "이마", "볼살", "턱살", "턱선", "이중턱", "피부", "잡티", "주근깨", "여드름",
            "팔뚝", "팔목", "손목", "허리", "뱃살", "가슴", "어깨", "승모근", "목주름", "다리", "종아리",
            "몸매", "체형", "잔머리", "헤어라인",
        )
    }
}
