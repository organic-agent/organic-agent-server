package com.soma.wes.retouch.domain

/**
 * 보정 회차가 어디까지 왔는지. 갤러리당 동시에 하나만 [COMPLETED]가 아닌 상태로 존재한다.
 */
enum class RetouchRoundStatus {

    /** 부부가 보정사진을 모으고 요청을 적는 중. 첫 담기 때 자동으로 만들어진다. */
    DRAFTING,

    /** 부부가 제출했다. 작가가 결과를 올리는 동안이라 요청 목록은 바뀌지 않는다. */
    REQUESTED,

    /** 작가가 결과를 다 올리고 끝냈다. 이때부터 다음 회차를 시작할 수 있다. */
    COMPLETED,
}
