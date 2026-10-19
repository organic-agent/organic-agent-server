package com.soma.wes.global.cluster

import java.time.Duration

/**
 * 리더 임대를 두는 곳(#336 ADR-0002). [LeasedSchedulerLeadership]이 갱신 주기·리더라고 믿는 시간·반납을 맡고, 이것은 한 번의 얻기와 반납만 한다.
 *
 * 전환 기간에는 `app.cluster.leader.store`로 Redis와 Postgres 중 하나를 고른다. Redis 쪽은 B4에서 지운다.
 */
interface LeaderLeaseStore {

    /** 임대가 비었거나 끝났거나 이미 [holder]의 것이면 [holder]의 것으로 [ttl] 동안 잡고 true. 남의 살아 있는 임대면 false. */
    fun claim(holder: String, ttl: Duration): Boolean

    /** [holder]의 임대일 때만 돌려준다 — 남의 임대를 지우지 않는다. */
    fun release(holder: String)
}
