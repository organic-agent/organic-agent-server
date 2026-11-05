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
class TestClock(
    start: Instant,
    private val zone: ZoneId = KST,
) : Clock() {

    private var instant: Instant = start

    constructor(start: ZonedDateTime) : this(start.toInstant(), start.zone)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = instant
}
