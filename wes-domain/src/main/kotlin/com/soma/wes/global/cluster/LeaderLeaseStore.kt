package com.soma.wes.global.cluster

import java.time.Duration

/**
 * 리더 임대를 두는 곳(#336 ADR-0002). [LeasedSchedulerLeadership]이 갱신 주기·리더라고 믿는 시간·반납을 맡고, 이것은 한 번의 얻기와 반납만 한다.
 *
 * 구현은 Postgres 행 하나([PostgresLeaderLeaseStore])다. Redis(ElastiCache) 구현은 전환을 마치고 지웠다(#336 B4) — 나눠 둔 덕에 리더 생애
 * 테스트가 닿지 않는 저장소를 흉내 낼 수 있다.
 */
interface LeaderLeaseStore {

    /** 임대가 비었거나 끝났거나 이미 [holder]의 것이면 [holder]의 것으로 [ttl] 동안 잡고 true. 남의 살아 있는 임대면 false. */
    fun claim(holder: String, ttl: Duration): Boolean

    /** [holder]의 임대일 때만 돌려준다 — 남의 임대를 지우지 않는다. */
    fun release(holder: String)
}
