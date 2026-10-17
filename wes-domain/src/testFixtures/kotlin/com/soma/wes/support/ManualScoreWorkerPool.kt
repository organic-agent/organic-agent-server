package com.soma.wes.support

import com.soma.wes.analysis.dto.ScoreWorkerDto
import com.soma.wes.analysis.dto.ScoreWorkerStateDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.ScoreWorkerPool
import java.time.ZonedDateTime

/**
 * 테스트용 워커 풀 — 인스턴스 목록을 테스트가 직접 놓고, 켜기·끄기 호출을 기록한다. 켜면 그 인스턴스가 PENDING이 되고
 * 끄면 STOPPING이 된다. 실제 점수 적재는 테스트가 픽스처로 흉내 낸다. 통합 테스트 컨텍스트의 기본 풀이다([FakeLlmConfig]).
 */
class ManualScoreWorkerPool : ScoreWorkerPool {

    val workers: MutableList<ScoreWorkerDto> = mutableListOf()
    val starts: MutableList<ZonedDateTime> = mutableListOf()
    val startedIds: MutableList<String> = mutableListOf()
    val stops: MutableList<String> = mutableListOf()
    override var isAvailable: Boolean = true
    var failNext: Boolean = false

    /** 켜기가 이 인스턴스에서는 늘 실패한다 — 그 영역에 GPU 재고가 없는 경우. */
    val failingStarts: MutableSet<String> = mutableSetOf()

    /** 켜기가 기록하는 시각. 테스트가 시계를 직접 다루지 않으므로 기본은 지금이다. */
    var now: () -> ZonedDateTime = { ZonedDateTime.now() }

    override fun snapshot(): List<ScoreWorkerDto> {
        failIfRequested()
        return workers.toList()
    }

    override fun start(instanceId: String) {
        failIfRequested()
        if (instanceId in failingStarts) throw AnalysisException(AnalysisErrorCode.SCORE_WORKER_CONTROL_FAILED)
        starts += now()
        startedIds += instanceId
        val index = workers.indexOfFirst { it.instanceId == instanceId }
        if (index >= 0) workers[index] = workers[index].copy(state = ScoreWorkerStateDto.PENDING, launchedAt = now())
    }

    override fun stop(instanceId: String) {
        failIfRequested()
        stops += instanceId
        val index = workers.indexOfFirst { it.instanceId == instanceId }
        if (index >= 0) workers[index] = workers[index].copy(state = ScoreWorkerStateDto.STOPPING)
    }

    fun worker(
        instanceId: String = "i-1",
        state: ScoreWorkerStateDto = ScoreWorkerStateDto.STOPPED,
        launchedAt: ZonedDateTime? = null,
        zone: String? = null,
    ) {
        workers.removeAll { it.instanceId == instanceId }
        workers += ScoreWorkerDto(instanceId = instanceId, state = state, launchedAt = launchedAt, zone = zone)
    }

    private fun failIfRequested() {
        if (failNext) {
            failNext = false
            throw AnalysisException(AnalysisErrorCode.SCORE_WORKER_CONTROL_FAILED)
        }
    }

    fun reset() {
        workers.clear()
        starts.clear()
        startedIds.clear()
        failingStarts.clear()
        stops.clear()
        isAvailable = true
        failNext = false
    }
}
