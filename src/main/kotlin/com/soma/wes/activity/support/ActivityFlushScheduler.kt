package com.soma.wes.activity.support

import com.soma.wes.activity.service.ActivityFlushService
import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 모아 둔 활동 시각을 주기마다, 그리고 앱이 내려갈 때 한 번 더 쓴다(#286 (f)). 주기 작업 스레드라 분석 풀 연결을 쓴다. */
@Component
@ConditionalOnProperty(prefix = "app.activity.buffer", name = ["enabled"], havingValue = "true")
class ActivityFlushScheduler(
    private val flushService: ActivityFlushService,
) {

    @Scheduled(fixedDelayString = "\${app.activity.buffer.flush-interval:PT5S}")
    fun flush() {
        flushService.flush()
    }

    @PreDestroy
    fun flushOnShutdown() {
        flushService.flush()
    }
}
