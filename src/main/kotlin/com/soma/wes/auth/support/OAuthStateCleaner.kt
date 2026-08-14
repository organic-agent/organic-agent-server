package com.soma.wes.auth.support

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 소비되지 않고 만료된 `oauth_states` 행을 주기적으로 걷어낸다.
 */
@Component
class OAuthStateCleaner(
    private val oAuthStateStore: OAuthStateStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 매시 정각. 10분짜리 수명에 비하면 넉넉하지만, 이 청소가 급할 이유가 없다. */
    @Scheduled(cron = "0 0 * * * *")
    fun purge() {
        val deleted = oAuthStateStore.purgeExpired()
        if (deleted > 0) {
            log.info("만료된 OAuth state {}건을 정리했다", deleted)
        }
    }
}
