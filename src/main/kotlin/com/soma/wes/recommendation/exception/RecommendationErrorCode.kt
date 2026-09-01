package com.soma.wes.recommendation.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class RecommendationErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /** 갤러리에 분석 잡을 한 번도 요청한 적이 없는 경우. 상태 조회가 돌려준다. */
    ANALYSIS_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION_404_1", "AI 분석을 요청한 적이 없습니다."),

    /**
     * 갤러리에 아직 끝나지 않은(PENDING·RUNNING) 분석 잡이 있는 경우.
     *
     * 같은 갤러리를 두 배치가 동시에 적재하면 백분위·클러스터 번호가 서로 다른 기준으로 섞인다.
     * 앞선 잡이 끝난 뒤 다시 요청하면 된다. DB의 부분 유니크가 최종 방어선이다.
     */
    ANALYSIS_JOB_ALREADY_ACTIVE(HttpStatus.CONFLICT, "RECOMMENDATION_409_1", "이미 진행 중인 AI 분석이 있습니다."),

    /**
     * 벡터가 적재된 사진이 한 장도 없는 경우.
     *
     * 분석은 임베딩 위에서 돈다 — 임베딩 실행(`POST /embeddings/run`)이 먼저다. 빈 잡을 만들어
     * 두면 배치가 0장을 처리하고 DONE으로 닫혀 "분석이 끝났다"처럼 보인다.
     */
    NO_EMBEDDED_PHOTOS(HttpStatus.CONFLICT, "RECOMMENDATION_409_2", "임베딩이 끝난 사진이 없어 AI 분석을 시작할 수 없습니다."),

    /**
     * 벡터가 없는 사진이 아직 남아 있는 경우.
     *
     * 분석은 갤러리 전수 기준으로 백분위·클러스터를 잡는다 — 일부만 넣고 돌리면 나중에 온 사진은
     * 이번 잡에서 빠지고 기준도 어긋난다. 임베딩 대상(PENDING 제외)이 전부 끝난 뒤에만 받는다.
     */
    EMBEDDING_NOT_COMPLETE(HttpStatus.CONFLICT, "RECOMMENDATION_409_3", "임베딩이 아직 끝나지 않은 사진이 있어 AI 분석을 시작할 수 없습니다."),

    /**
     * naming 잡을 요청했는데 full 잡이 DONE인 적이 없는 경우.
     *
     * 이름 붙이기는 full 잡이 남긴 임베딩 그룹·CLIP 벡터 위에서 돈다 — 사진별 분석이 먼저다.
     */
    FULL_ANALYSIS_NOT_DONE(HttpStatus.CONFLICT, "RECOMMENDATION_409_4", "사진별 분석(full)이 끝난 적이 없어 이름 붙이기를 시작할 수 없습니다."),
}
