package com.soma.wes.analysis.service.port

import com.soma.wes.analysis.dto.OpsAlertDto

/**
 * 운영 알림([OpsAlertDto])을 운영 채널(디스코드 웹훅)로 보낸다. 사용자 알림(`UserNotificationPublisher`)과 다르다 —
 * 받는 사람이 고객이 아니라 운영자다. 무엇을 언제 알릴지는 호출자가 정한다.
 */
interface OpsAlertSender {

    /** 보낼 채널이 설정돼 있는지. 로컬·테스트에는 없는 것이 정상이라 기동을 막지 않는다. */
    fun isAvailable(): Boolean

    /** 알림을 보낸다. 전송 자체가 실패하면 도메인 예외를 던진다 — 삼킬지는 호출자가 정한다. */
    fun send(alert: OpsAlertDto)
}
