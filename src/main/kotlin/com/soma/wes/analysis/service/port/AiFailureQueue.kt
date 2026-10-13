package com.soma.wes.analysis.service.port

import com.soma.wes.analysis.dto.AiFailureMessageDto

/**
 * 분석 Lambda 실패 큐(#286 (c) ②). Lambda 가 끝내 실패한 호출을 OnFailure 로 넣고, 이 서버가 꺼내 재전송 대기를 앞당긴다.
 * 큐는 서버 여러 대에 메시지를 나눠 주므로 한 메시지는 한 서버가 처리한다(지울 때까지 다른 서버에 보이지 않는다).
 */
interface AiFailureQueue {
    /** 메시지를 받는다. 없으면 기다리다(롱 폴링) 빈 목록으로 돌아온다. */
    fun receive(): List<AiFailureMessageDto>

    /** 처리한 메시지를 지운다. 지우지 않은 메시지는 가시성 시간 뒤 다시 보인다. */
    fun delete(receiptHandle: String)
}
