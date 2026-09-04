package com.soma.wes.recommendation.service

import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 잡 id를 실행기에 넘긴다. 같은 잡이 두 경로(요청 직후·스윕)로 들어와도 한 번만 큐에 서게 하는 것이 여기 일이고,
 * 실제로 두 번 도는 것은 [AiSelectionJobRunner]의 claim이 막는다.
 */
@Service
class AiSelectionJobLauncher(
    private val executor: AiJobExecutor,
    private val runner: AiSelectionJobRunner,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun launch(jobId: Long) {
        if (!inFlight.add(jobId)) return
        try {
            executor.submit {
                try {
                    runner.run(jobId)
                } finally {
                    inFlight.remove(jobId)
                }
            }
        } catch (e: TaskRejectedException) {
            inFlight.remove(jobId)
            log.warn("AI 추천 잡 큐가 가득 참 — 스윕이 다시 집는다: jobId={}", jobId)
        }
    }
}
