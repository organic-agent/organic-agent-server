package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.repository.AiSelectionJobRepository
import com.soma.wes.recommendation.service.AiSelectionJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 잡이 실행기 밖에서 잊히지 않게 한다.
 * - 기동 시: 이전 프로세스가 도중에 죽어 RUNNING으로 남은 잡을 PENDING으로 되돌리고, PENDING 전부를 다시 줄에 세운다.
 * - 주기적으로: 큐가 가득 차 거부됐거나 after-commit 트리거가 유실된 PENDING을 집는다. 같은 잡을 두 번 돌리는 것은
 *   [com.soma.wes.recommendation.service.AiSelectionJobRunner]의 claim이 막는다.
 */
@Component
class AiSelectionJobRecovery(
    private val aiSelectionJobRepository: AiSelectionJobRepository,
    private val launcher: AiSelectionJobLauncher,
    private val transactionTemplate: TransactionTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() {
        val orphans = requeueRunning()
        if (orphans > 0) log.warn("실행 중 죽은 AI 추천 잡 {}건을 PENDING으로 되돌림", orphans)
        sweepPending()
    }

    @Scheduled(fixedDelayString = SWEEP_DELAY)
    fun sweepPending() {
        aiSelectionJobRepository.findAllByStatusOrderByIdAsc(AiJobStatus.PENDING).forEach { launcher.launch(it.requiredId) }
    }

    /** 자기 호출은 프록시를 지나지 않으므로 애노테이션이 아니라 템플릿으로 트랜잭션을 연다. */
    private fun requeueRunning(): Int = transactionTemplate.execute {
        val running = aiSelectionJobRepository.findAllByStatusOrderByIdAsc(AiJobStatus.RUNNING)
        running.forEach { it.requeue() }
        running.size
    }!!

    companion object {
        private const val SWEEP_DELAY = "PT30S"
    }
}
