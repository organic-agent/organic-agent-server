package com.soma.wes.recommendation.service

import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.repository.AiSelectionJobRepository
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 잡이 실행기 밖에서 잊히지 않게 한다. 기동 시([com.soma.wes.recommendation.listener.AiSelectionJobRecoveryListener])와
 * 30초마다([com.soma.wes.recommendation.scheduler.AiSelectionJobRecoveryScheduler]) 불린다.
 * - 돌리던 서버가 도중에 죽어 RUNNING으로 남은 잡을 PENDING으로 되돌리고, PENDING 전부를 다시 줄에 세운다.
 * - 큐가 가득 차 거부됐거나 after-commit 트리거가 유실된 PENDING도 여기서 집힌다. 같은 잡을 두 번 돌리는 것은
 *   [AiSelectionJobRunner]의 claim이 막는다.
 *
 * RUNNING은 [STALE_AFTER]보다 오래 집힌 것만 되돌린다(#313). 서버가 여럿이면 새 서버가 뜰 때(배포·늘리기·교체) 다른 서버가 지금 돌리는
 * 잡까지 되돌려 같은 잡을 두 번 돌리게 된다. 대신 서버가 한 대일 때 재시작으로 끊긴 잡은 [STALE_AFTER]만큼 늦게 다시 돈다.
 */
@Service
class AiSelectionJobRecoveryService(
    private val aiSelectionJobRepository: AiSelectionJobRepository,
    private val launcher: AiSelectionJobLauncher,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 집힌 뒤 이만큼 지나도 RUNNING 이면 돌리던 서버가 죽었다고 본다. 잡은 DB 계산뿐이라 보통 수 초 안에 끝난다. */
        private val STALE_AFTER: Duration = Duration.ofMinutes(5)
    }

    fun recover() {
        val orphans = requeueStaleRunning()
        if (orphans > 0) log.warn("실행 중 죽은 AI 추천 잡 {}건을 PENDING으로 되돌림", orphans)
        aiSelectionJobRepository.findAllByStatusOrderByIdAsc(AiJobStatus.PENDING).forEach { launcher.launch(it.requiredId) }
    }

    /** 자기 호출은 프록시를 지나지 않으므로 애노테이션이 아니라 템플릿으로 트랜잭션을 연다. */
    private fun requeueStaleRunning(): Int = transactionTemplate.execute {
        val now = ZonedDateTime.now(clock)
        aiSelectionJobRepository.requeueStale(before = now.minus(STALE_AFTER), now = now)
    } ?: 0
}
