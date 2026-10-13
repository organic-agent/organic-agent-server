package com.soma.wes.gallery.support

import com.soma.wes.gallery.service.GalleryLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "app.gallery-lifecycle", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class GalleryLifecycleScheduler(private val lifecycle: GalleryLifecycleService) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var afterId = 0L

    /** 커서를 다음 실행까지 유지해 큰 작업공간에서도 앞쪽 알림 대상만 반복하지 않는다. */
    @Scheduled(fixedDelayString = "\${app.gallery-lifecycle.sweep-interval:PT1H}", initialDelayString = "PT1M")
    fun sweep() {
        repeat(MAX_BATCHES) {
            val ids = lifecycle.nextBatch(afterId)
            if (ids.isEmpty()) {
                afterId = 0L
                return
            }
            ids.forEach { id ->
                try {
                    lifecycle.process(id)
                } catch (exception: RuntimeException) {
                    log.warn("갤러리 {} 만료/알림 처리 실패 — 다음 순회에서 재시도합니다", id, exception)
                }
            }
            afterId = ids.last()
        }
    }

    companion object {
        /** 한 스윕이 DB를 장시간 독점하지 않도록 묶음 수를 제한한다. */
        private const val MAX_BATCHES = 10
    }
}
