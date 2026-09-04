package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.service.AiJobExecutor
import org.springframework.beans.factory.DisposableBean
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

/**
 * 추천 잡 전용 스레드풀. 인스턴스 한 대라 스레드 둘이면 동시 갤러리 둘까지 돌고 나머지는 줄을 선다 —
 * 큐가 차서 거부되면 잡은 PENDING으로 남고 스윕([AiSelectionJobRecovery])이 다시 집는다.
 * 종료 시 30초 안에 도는 라운드를 기다린다. 못 끝내면 RUNNING 고아가 되고 기동 복구가 PENDING으로 되돌린다.
 */
@Component
class ThreadPoolAiJobExecutor : AiJobExecutor, DisposableBean {

    private val executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = THREADS
        maxPoolSize = THREADS
        queueCapacity = QUEUE_CAPACITY
        setThreadNamePrefix("ai-job-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS)
        initialize()
    }

    override fun submit(task: Runnable) {
        executor.execute(task)
    }

    override fun destroy() {
        executor.shutdown()
    }

    companion object {
        private const val THREADS = 2
        private const val QUEUE_CAPACITY = 100
        private const val AWAIT_TERMINATION_SECONDS = 30
    }
}
