package com.soma.wes.agent.support

import com.soma.wes.agent.config.AgentProperties
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class AgentQuestionLimiterUnitTest {

    private val clock = MovableClock(Instant.parse("2026-10-16T00:00:00Z"))
    private val limiter = AgentQuestionLimiter(AgentProperties(maxQuestions = 2, window = Duration.ofMinutes(10)), clock)

    @Test
    fun `창 안에서 상한을 넘으면 거절한다`() {
        // given
        limiter.acquire(userId = 1)
        limiter.acquire(userId = 1)

        // when & then
        assertThatThrownBy { limiter.acquire(userId = 1) }
            .isInstanceOf(AgentException::class.java)
            .extracting("errorCode")
            .isEqualTo(AgentErrorCode.TOO_MANY_QUESTIONS)
    }

    @Test
    fun `사용자마다 따로 센다`() {
        // given
        limiter.acquire(userId = 1)
        limiter.acquire(userId = 1)

        // when & then
        assertThatCode { limiter.acquire(userId = 2) }.doesNotThrowAnyException()
    }

    @Test
    fun `창이 지나간 질문은 세지 않는다`() {
        // given
        limiter.acquire(userId = 1)
        limiter.acquire(userId = 1)
        clock.now = clock.now.plus(Duration.ofMinutes(10))

        // when & then
        assertThatCode { limiter.acquire(userId = 1) }.doesNotThrowAnyException()
    }

    private class MovableClock(var now: Instant) : Clock() {

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}
