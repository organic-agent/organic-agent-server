package com.soma.wes.recommendation.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class RecommendationErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    // 분석 잡(ANALYSIS_*, RECOMMENDATION_404_1·409_1~4)은 analysis 도메인의 AnalysisErrorCode로 옮겨 갔다. 코드 문자열은 그대로다.
    // 비교샷(RECOMMENDATION_400_1·404_3·409_7)은 기능과 함께 지웠다. 번호는 재사용하지 않는다 — 클라이언트가 옛 코드로 분기할 수 있다.

    /**
     * 추천을 요청했는데 갤러리에 AI 폴더 세트가 없는 경우(만든 적 없거나 전부 지움).
     *
     * 추천은 폴더 단위다(폴더마다 상위 n장) — 세트 없이 돌릴 폴백을 두면 "폴더별 추천"이 두 가지
     * 모양이 된다. 폴더 생성(POST /folder-groups/ai)이 먼저다.
     */
    FOLDER_SET_NOT_READY(HttpStatus.CONFLICT, "RECOMMENDATION_409_5", "AI 폴더 세트가 없어 추천을 시작할 수 없습니다."),

    /**
     * 셀렉에 아직 끝나지 않은(PENDING·RUNNING) 추천 잡이 있는 경우.
     *
     * 같은 셀렉에 두 라운드가 동시에 적히면 어느 쪽이 최신인지 말할 수 없다. DB의 부분 유니크가
     * 최종 방어선이다.
     */
    SELECTION_JOB_ALREADY_ACTIVE(HttpStatus.CONFLICT, "RECOMMENDATION_409_6", "이미 진행 중인 AI 추천이 있습니다."),

    /** 요청이 콕 집은 AI 폴더 세트(analysisJobId)가 이 갤러리에 없는 경우. 지운 세트도 없는 것이다. */
    FOLDER_SET_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION_404_2", "요청한 AI 폴더 세트를 찾을 수 없습니다."),
    DETAIL_FOLDER_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION_404_4", "추천할 세부폴더를 찾을 수 없습니다."),

    INVALID_QUERY(HttpStatus.BAD_REQUEST, "RECOMMENDATION_400_2", "추천 문장은 1~1000자, 장수는 1~500이어야 합니다."),
    QUERY_NOT_UNDERSTOOD(HttpStatus.UNPROCESSABLE_ENTITY, "RECOMMENDATION_422_1", "추천할 폴더와 장수를 명확히 지정해 주세요. 폴더 범위와 장수 조건만 지원합니다."),
    QUERY_AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "RECOMMENDATION_503_1", "자연어 추천을 사용할 수 없습니다. 폴더와 장수를 직접 지정해 주세요."),

    /**
     * LLM 호출이 문장을 돌려주지 못한 경우(타임아웃, 스로틀링, 거부, 잘림, JSON 아님, 설정 꺼짐).
     *
     * 추천 이유 문장에서는 사용자에게 나가지 않는다 — 그 사진만 폴백(템플릿) 문장으로 흡수한다.
     * 어댑터가 벤더 예외를 이 하나로 번역하므로 호출자는 실패의 종류를 가리지 않는다.
     */
    LLM_CALL_FAILED(HttpStatus.BAD_GATEWAY, "RECOMMENDATION_502_1", "AI 호출에 실패했습니다."),
}
