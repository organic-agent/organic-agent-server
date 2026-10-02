package com.soma.wes.analysis.domain

/**
 * 분석 잡이 FAILED 로 닫힌 이유. `analysis_jobs.error_code` 에 이름 그대로 저장되고 응답에도 그대로 나간다 —
 * web 이 코드로 화면을 가르고, 운영 알림이 코드로 거른다. 내부 문장(`error`)은 DB 에만 남고 사용자에게는 [userMessage]가 나간다.
 *
 * [transient]는 "같은 입력으로 다시 돌리면 될 수 있는가"다. 자동 재시도가 이 값으로 다시 돌릴 잡을 고른다.
 */
enum class AnalysisFailureCode(
    val userMessage: String,
    val transient: Boolean,
) {

    /** 분석할 사진이 한 장도 남지 않았다(전부 실패·삭제). 다시 돌려도 같다. */
    NOTHING_TO_ANALYZE("분석할 수 있는 사진이 없어요. 사진을 올린 뒤 다시 시도해 주세요.", transient = false),

    /** 임베딩·점수가 오래 진행되지 않았고 뒤처진 사진이 떼어 낼 수 있는 양을 넘었다 — 사진이 아니라 실행기(Lambda·GPU 워커)의 문제다. */
    SCORE_STAGE_DOWN("AI 분석이 끝나지 못했어요. 잠시 뒤 다시 시도해 주세요.", transient = true),

    /** categorize 가 시간 안에 결과를 내지 못했다(재전송 상한 또는 잡 전체 기한). */
    CATEGORIZE_TIMEOUT("AI 정리가 끝나지 못했어요. 잠시 뒤 다시 시도해 주세요.", transient = true),

    /** categorize Lambda 가 실패를 남겼다. */
    CATEGORIZE_FAILED("AI 정리가 끝나지 못했어요. 잠시 뒤 다시 시도해 주세요.", transient = true),

    /** 분류 결과로 폴더를 만들지 못했다. 같은 결과로 다시 만들어도 같으므로 다시 돌리지 않는다. */
    FOLDER_FAILED("AI 폴더를 만들지 못했어요. 문제가 계속되면 문의해 주세요.", transient = false),
    ;

    companion object {
        /** 코드 없이 닫힌 옛 잡에 내보내는 문장. */
        const val UNKNOWN_USER_MESSAGE = "AI 분석이 끝나지 못했어요. 다시 시도해 주세요."
    }
}
