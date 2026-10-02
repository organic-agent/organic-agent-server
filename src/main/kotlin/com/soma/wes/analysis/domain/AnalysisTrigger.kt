package com.soma.wes.analysis.domain

/** 분석 잡을 누가 만들었나. `analysis_jobs.trigger` 에 이름 그대로 저장되고 로그(`job.created`)에도 나간다. */
enum class AnalysisTrigger {

    /** 분석 요청 API — 작가의 버튼, 또는 web 이 업로드 직후 보내는 요청. */
    USER,

    /** 업로드가 조용해진 갤러리에 서버가 스스로 만든 잡. 브라우저가 닫혀도 폴더가 만들어지게 한다. */
    AUTO,

    /** 일시적 실패로 닫힌 잡을 서버가 다시 돌리는 잡. */
    RETRY,
}
