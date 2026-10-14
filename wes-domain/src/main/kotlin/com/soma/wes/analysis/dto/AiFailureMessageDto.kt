package com.soma.wes.analysis.dto

/** 실패 큐에서 받은 메시지 하나. [failure]가 null 이면 읽을 수 없는 메시지다. 처리한 뒤 [receiptHandle]로 지운다. */
data class AiFailureMessageDto(
    val receiptHandle: String,
    val failure: AiFailureDto?,
)
