package com.soma.wes.analysis.domain

/**
 * 분석 잡의 이력 한 줄의 종류. 로그의 이벤트 이름과 짝이다 — 로그는 7일 뒤 사라지고 이 행은 잡과 함께 남는다.
 * DB 에는 이름이 문자열로 들어가므로 이름을 바꾸면 옛 행이 읽히지 않는다. 바꾸지 말고 새로 더한다.
 */
enum class AnalysisJobEventType {
    /** 잡이 만들어졌다 (`job.created`). */
    CREATED,

    /** 점수가 다 차서 categorize 를 처음 보냈다 (ANALYZING → CATEGORIZING). */
    CATEGORIZE_SENT,

    /** 결과가 늦어 categorize 를 다시 보냈다. */
    CATEGORIZE_RESENT,

    /** categorize 를 보내는 것 자체가 실패했다. 다음 회차가 다시 보낸다. */
    CATEGORIZE_SEND_FAILED,

    /** 진행이 멈춘 사진 몇 장을 떼어 냈다 (`job.stalled action=detach`). */
    PHOTOS_DETACHED,

    /** GPU 워커가 점수를 내지 못해 Lambda 폴백을 보냈다 (`score.fallback`). */
    SCORE_FALLBACK,

    /** 폴더에 사진을 넣었다 (`folder.materialized`). */
    FOLDER_MATERIALIZED,

    /** 폴더 만들기가 예상 밖 예외로 실패했다. 상한까지 다시 시도한다. */
    MATERIALIZE_FAILED,

    /** 잡이 정상으로 닫혔다. */
    DONE,

    /** 잡이 실패로 닫혔다. */
    FAILED,

    /** 닫힌(DONE) 잡의 갤러리에 화질 점수가 다 차서 categorize rank 모드를 보냈다 (`rank.sent`). 다시 보낼 때도 같은 종류다. */
    RANK_SENT,

    /** rank 모드를 보내는 것 자체가 실패했다. 다음 회차가 다시 보낸다. */
    RANK_SEND_FAILED,
}
