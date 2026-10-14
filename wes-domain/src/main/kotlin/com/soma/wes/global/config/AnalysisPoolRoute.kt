package com.soma.wes.global.config

import org.springframework.core.task.TaskDecorator
import org.springframework.stereotype.Component

/**
 * 지금 스레드가 분석 쪽 작업 중인지 표시한다. [AnalysisRoutingDataSource]가 연결을 꺼낼 때 이 표시로 풀을 고른다(#286 (a)).
 *
 * 표시는 실행기에 거는 [taskDecorator]가 작업 하나를 감싸는 동안만 산다 — 스레드 자체를 표시해 두지 않는 이유는 실행기 스레드가
 * 재사용되기 때문이다. 분석 풀을 끈 환경(관리자 앱, `app.datasource.analysis-pool.enabled=false`)에서는 표시만 남고 효과가 없다.
 */
@Component
class AnalysisPoolRoute {

    private val analysis = ThreadLocal<Boolean>()

    /** 작업 하나를 분석 쪽으로 표시해 돌린다. 주기 작업 스케줄러와 폴더 단계 실행기에 건다. */
    val taskDecorator = TaskDecorator { task -> Runnable { asAnalysis { task.run() } } }

    fun isAnalysis(): Boolean = analysis.get() == true

    fun <T> asAnalysis(block: () -> T): T {
        val previous = analysis.get()
        analysis.set(true)
        try {
            return block()
        } finally {
            if (previous == null) analysis.remove() else analysis.set(previous)
        }
    }
}
