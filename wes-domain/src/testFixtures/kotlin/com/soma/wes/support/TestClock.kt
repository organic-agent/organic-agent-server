package com.soma.wes.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 테스트가 직접 앞당기는 시계. 파일마다 가변 시계를 따로 만들지 않고 이것을 쓴다.
 *
 * 시작 시각은 호출자가 정한다 — DB 행의 시각과 맞물리는 통합 테스트는 주입한 시계의 지금(`ZonedDateTime.now(clock)`)을,
 * DB를 거치지 않는 테스트는 고정 시각을 넘긴다.
 */
class TestClock private constructor(
    private val state: State,
    private val zone: ZoneId,
) : Clock() {

    constructor(start: Instant, zone: ZoneId = KST) : this(State(start), zone)

    constructor(start: ZonedDateTime) : this(start.toInstant(), start.zone)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    /** [withZone]으로 만든 시계도 같은 시각을 함께 앞당기도록 시각을 한 곳에 둔다. */
    private class State(var instant: Instant)

    fun advance(duration: Duration) {
        state.instant = state.instant.plus(duration)
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = if (zone == this.zone) this else TestClock(state, zone)

    override fun instant(): Instant = state.instant
}
