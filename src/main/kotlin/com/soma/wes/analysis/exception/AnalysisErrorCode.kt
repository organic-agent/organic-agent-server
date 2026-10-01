package com.soma.wes.analysis.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

/**
 * 코드 문자열은 이 도메인이 `recommendation`·`photo`에서 갈라져 나올 때 프론트가 이미 분기하던 값을 그대로 둔 것이다
 * (`RECOMMENDATION_409_1`, `PHOTO_503_1`). 접두사를 `ANALYSIS_`로 바꾸는 것은 프론트와 같이 정한다.
 */
enum class AnalysisErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /** 갤러리에 분석 잡을 한 번도 요청한 적이 없는 경우. 상태 조회가 돌려준다. */
    ANALYSIS_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION_404_1", "AI 분석을 요청한 적이 없습니다."),

    /**
     * 갤러리에 아직 끝나지 않은(ANALYZING·CATEGORIZING) 분석 잡이 있는 경우.
     *
     * 같은 갤러리를 두 categorize가 동시에 적재하면 백분위·그룹 번호가 서로 다른 기준으로 섞인다.
     * 앞선 잡이 끝난 뒤 다시 요청하면 된다. DB의 부분 유니크가 최종 방어선이다.
     */
    ANALYSIS_JOB_ALREADY_ACTIVE(HttpStatus.CONFLICT, "RECOMMENDATION_409_1", "이미 진행 중인 AI 분석이 있습니다."),

    /**
     * 분석 대상(업로드가 끝났고 실패하지 않은) 사진이 한 장도 없는 경우.
     *
     * 빈 잡을 만들어 두면 관측이 곧바로 닫혀 "분석이 끝났다"처럼 보인다.
     * URL만 발급된 PENDING 사진은 S3 객체가 없을 수 있어 세지 않는다.
     */
    NO_PHOTOS_TO_ANALYZE(HttpStatus.CONFLICT, "RECOMMENDATION_409_2", "업로드가 끝난 사진이 없어 AI 분석을 시작할 수 없습니다."),

    /**
     * Lambda·서브프로세스 호출 자체가 실패한 경우(권한·스로틀링·함수 없음). 계산 실패가 아니다 —
     * 둘을 같은 코드로 돌려주면 "Lambda 로그를 볼 것"과 "IAM을 볼 것"을 구분할 수 없다.
     * 잡 경로에서는 사용자에게 나가지 않고 스윕이 다시 부른다. 관리자 재처리 경로만 그대로 돌려준다.
     */
    AI_TASK_SEND_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_1", "AI 분석 실행을 시작하지 못했습니다."),

    /**
     * GPU 워커 인스턴스를 켜거나 끄는 호출 자체가 실패한 경우(EC2 권한·API 오류). 스윕이 잡아 로그로 남기고 다음 걸음에 다시 본다 —
     * 점수는 그동안 score Lambda 폴백이 낸다.
     */
    SCORE_WORKER_CONTROL_FAILED(HttpStatus.BAD_GATEWAY, "PHOTO_502_6", "GPU 워커를 제어하지 못했습니다."),

    /** 잡이 지나는 호출(embedder·categorize, GPU가 없으면 score) 중 실행기가 설정되지 않은 것이 있는 경우. 로컬·테스트에는 Lambda가 없는 것이 정상이다. */
    AI_TASK_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "PHOTO_503_1", "AI 분석 실행이 설정되지 않았습니다."),
}
