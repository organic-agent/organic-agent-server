package com.soma.wes.auth.support

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 소비되지 않고 만료된 `oauth_states` 행을 주기적으로 걷어낸다.
 *
 * 동의 화면에서 창을 닫아버린 로그인은 콜백이 돌아오지 않아 행이 그대로 남는다. 수명이
 * 10분이라 금방 무의미해지지만 지워주는 사람이 없으면 테이블만 계속 커진다.
 *
 * 트랜잭션은 [OAuthStateStore.purgeExpired]가 갖는다 — 여기서 `@Transactional`을 걸면
 * 프록시를 거치지 않는 스케줄러 호출이라 의도대로 동작하지 않을 수 있다.
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
