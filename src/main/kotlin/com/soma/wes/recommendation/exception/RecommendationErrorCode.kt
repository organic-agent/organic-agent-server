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

    COMPARE_SAME_PHOTO(HttpStatus.BAD_REQUEST, "RECOMMENDATION_400_1", "같은 사진 두 장은 비교할 수 없습니다."),

    /** 비교 대상 사진이 이 갤러리에 없는 경우. 휴지통에 들어간 사진도 없는 것이다. */
    COMPARE_PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION_404_3", "비교할 사진을 찾을 수 없습니다."),

    /**
     * 비교 대상 중 AI 분석이 끝나지 않은 사진이 있는 경우.
     *
     * 판정의 재료(초점·백분위·연사 관계)가 `photo_analysis`의 분석 컬럼이다 — FULL 분석이 먼저다.
     * AI 쪽에서도 같은 이유로 죽지만, 5초를 기다리게 한 뒤 실패하는 것보다 여기서 거절하는 게 낫다.
     */
    COMPARE_NOT_ANALYZED(HttpStatus.CONFLICT, "RECOMMENDATION_409_7", "AI 분석이 끝나지 않은 사진이라 비교할 수 없습니다."),

    /**
     * 판정 실행이 시작됐지만 실패한 경우(프로세스 오류, Lambda 함수 오류, 계약을 벗어난 응답).
     *
     * 템플릿 폴백은 AI 쪽 안에서 처리되므로, 여기까지 온 실패는 실행 경로 자체의 문제다.
     */
    COMPARE_FAILED(HttpStatus.BAD_GATEWAY, "RECOMMENDATION_502_1", "AI 비교 판정에 실패했습니다."),

    /** 실행기(Lambda·로컬 스크립트)가 설정되지 않은 경우. 로컬·테스트에는 없는 것이 정상이라 호출 시점에 실패한다. */
    COMPARE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "RECOMMENDATION_503_1", "AI 비교가 설정되지 않았습니다."),
}
