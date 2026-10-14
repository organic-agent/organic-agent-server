package com.soma.wes.analysis.dto

/** EC2 인스턴스 상태를 이 도메인이 보는 네 값으로 접은 것. `shutting-down`·`terminated`는 [STOPPED]로 본다 — 켤 수 없는 것은 같다. */
enum class ScoreWorkerStateDto {
    PENDING,
    RUNNING,
    STOPPING,
    STOPPED,
}
