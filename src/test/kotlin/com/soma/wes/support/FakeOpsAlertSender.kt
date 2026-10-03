package com.soma.wes.support

import com.soma.wes.analysis.dto.OpsAlertDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.OpsAlertSender
import java.util.Collections

/** 테스트용 운영 알림 — 보낸 알림을 기록만 한다. [available]로 채널 없음을, [failNext]로 웹훅 실패를 재현한다. */
class FakeOpsAlertSender : OpsAlertSender {

    val alerts: MutableList<OpsAlertDto> = Collections.synchronizedList(mutableListOf())
    var available: Boolean = true
    var failNext: Boolean = false

    override fun isAvailable(): Boolean = available

    override fun send(alert: OpsAlertDto) {
        if (failNext) {
            failNext = false
            throw AnalysisException(AnalysisErrorCode.OPS_ALERT_SEND_FAILED)
        }
        alerts += alert
    }

    fun reset() {
        alerts.clear()
        available = true
        failNext = false
    }
}
