package com.soma.wes.trash.support

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 보관 기간이 지난 휴지통 행을 주기적으로 걷는다.
 *
 * 실제 절차와 로깅은 [TrashEraser.purgeExpired]에 있다. 인스턴스마다 독립으로 돌므로 여러 대로
 * 늘리면 중복 실행되지만, S3 삭제는 없는 키에도 성공하고 없는 행의 DELETE는 no-op이라
 * 낭비일 뿐 결과는 같다.
 */
@Component
class TrashPurgeScheduler(
    private val trashEraser: TrashEraser,
) {

    /** 매시 30분. OAuthStateCleaner(정각)와 분만 어긋나게 둔다. 이 청소가 급할 이유는 없다. */
    @Scheduled(cron = "0 30 * * * *")
    fun purge() {
        trashEraser.purgeExpired()
    }
}
