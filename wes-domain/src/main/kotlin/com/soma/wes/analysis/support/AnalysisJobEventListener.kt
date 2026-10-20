package com.soma.wes.analysis.support

import com.soma.wes.analysis.dto.AnalysisJobEventDto
import com.soma.wes.global.config.AnalysisPoolRoute
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [AnalysisJobEventRecorder]가 낸 이력을 부른 쪽 트랜잭션이 커밋된 뒤 이력 스레드 하나에 넘긴다(#346).
 *
 * 부른 스레드에서 바로 쓰지 않는 이유: 커밋 뒤 콜백이 도는 동안 Spring 은 부른 쪽 연결을 아직 놓지 않는다. 거기서 새 트랜잭션을 열면
 * 요청 하나가 연결 둘을 쥐고, 한 서버에 "AI 분석" 요청이 풀 크기만큼 몰리면 서로 두 번째 연결을 기다리다 연결 대기 시간(30초)까지 멈췄다.
 * 넘기고 돌아오면 부른 쪽은 연결을 바로 놓고, 이력 스레드는 연결 하나만 빌린다.
 * - 스레드가 하나라 부른 순서대로 쓰인다. 분석 풀 표시를 걸어 사용자 요청 풀과 겹치지 않는다(관리자 앱에선 표시만 남는다).
 * - 이력은 운영 확인용이라(앱 동작이 읽지 않는다) 늦게 쓰이거나 빠져도 된다. 큐가 차거나 쓰기가 실패하면 버리고 로그만 남긴다 —
 *   이력 때문에 부른 쪽이 실패하거나 기다리지 않게.
 * - 트랜잭션 밖에서 낸 이력은 바로 넘긴다(`fallbackExecution`).
 */
@Component
class AnalysisJobEventListener(
    private val writer: AnalysisJobEventWriter,
    analysisPoolRoute: AnalysisPoolRoute,
) : DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 넘겼지만 아직 쓰기를 마치지 않은 이력 수. */
    private val pending = AtomicInteger()

    private val executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = THREADS
        maxPoolSize = THREADS
        queueCapacity = QUEUE_CAPACITY
        setThreadNamePrefix("job-event-")
        setTaskDecorator(analysisPoolRoute.taskDecorator)
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS)
        initialize()
    }

    companion object {
        private const val THREADS = 1
        private const val QUEUE_CAPACITY = 1_000
        private const val AWAIT_TERMINATION_SECONDS = 10
        private val AWAIT_IDLE_POLL = Duration.ofMillis(10)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: AnalysisJobEventDto) {
        pending.incrementAndGet()
        try {
            executor.execute { write(event) }
        } catch (e: TaskRejectedException) {
            pending.decrementAndGet()
            log.warn("분석 잡 {} 이력을 버렸다 type={} — 이력 큐가 찼다", event.jobId, event.type)
        }
    }

    /** 넘긴 이력이 다 쓰일 때까지 기다린다. 시간 안에 다 쓰였으면 true. 테스트가 비동기 기록을 기다릴 때 쓴다. */
    fun awaitIdle(timeout: Duration = Duration.ofSeconds(5)): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(AWAIT_IDLE_POLL)
        }
        return true
    }

    override fun destroy() {
        executor.shutdown()
    }

    private fun write(event: AnalysisJobEventDto) {
        try {
            writer.write(event)
        } catch (e: RuntimeException) {
            log.error("분석 잡 {} 이력 기록 실패 type={} — 파이프라인은 계속한다", event.jobId, event.type, e)
        } finally {
            pending.decrementAndGet()
        }
    }
}
