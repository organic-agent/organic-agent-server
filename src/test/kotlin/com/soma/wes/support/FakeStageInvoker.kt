package com.soma.wes.support

import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.StageInvoker

/**
 * 테스트용 실행기 — 호출을 기록만 하고 아무것도 돌리지 않는다. Lambda가 DB에 쓰는 일은 테스트가 직접 흉내 낸다.
 * [available]로 설정 없음(503)을, [failNext]로 호출 실패(502)를 재현한다.
 */
class FakeStageInvoker : StageInvoker {

    data class Call(val stage: AnalysisStage, val jobId: Long, val galleryId: Long, val force: Boolean)

    val calls: MutableList<Call> = mutableListOf()
    var available: Boolean = true
    var failNext: Boolean = false

    override fun isAvailable(stage: AnalysisStage): Boolean = available

    override fun invoke(stage: AnalysisStage, jobId: Long, galleryId: Long, force: Boolean) {
        if (failNext) {
            failNext = false
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        calls += Call(stage = stage, jobId = jobId, galleryId = galleryId, force = force)
    }

    fun callsOf(jobId: Long): List<Call> = calls.filter { it.jobId == jobId }

    fun reset() {
        calls.clear()
        available = true
        failNext = false
    }
}
