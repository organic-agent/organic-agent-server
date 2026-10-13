package com.soma.wes.support

import com.soma.wes.recommendation.service.port.AiJobExecutor

/** 테스트용 실행기 — 넘어온 잡을 바로 돌리지 않고 모아 둔다. 요청 API는 PENDING을 돌려주고, 테스트가 [runAll]로 실행 시점을 정한다. */
class ManualAiJobExecutor : AiJobExecutor {

    private val queue = ArrayDeque<Runnable>()

    val pendingCount: Int
        get() = queue.size

    override fun submit(task: Runnable) {
        queue.addLast(task)
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    fun reset() {
        queue.clear()
    }
}
