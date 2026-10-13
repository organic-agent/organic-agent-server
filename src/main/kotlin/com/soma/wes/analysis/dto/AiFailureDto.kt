package com.soma.wes.analysis.dto

import java.time.ZonedDateTime

/**
 * Lambda 가 끝내 실패한 비동기 호출 하나 — OnFailure 로 실패 큐에 들어온 호출 기록을 이 서버의 말로 옮긴 것(#286 (c)).
 *
 * [task]는 그때 보냈던 작업(원본 페이로드)이다. 모르는 함수·모양이면 null 이다 — 기록만 하고 버린다.
 * [condition]은 Lambda 가 붙인 실패 사유(`RetriesExhausted` · `EventAgeExceeded`)이고, [failedAt]은 Lambda 가 실패를 확정한 시각이다.
 * 그 뒤에 다시 배정·전송된 작업은 이 실패의 대상이 아니다.
 */
data class AiFailureDto(
    val task: AiTaskDto?,
    val functionName: String,
    val condition: String,
    val errorType: String?,
    val errorMessage: String?,
    val failedAt: ZonedDateTime,
)
