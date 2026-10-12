package com.soma.wes.global.logging

import com.soma.wes.support.CapturedLogs
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 부하 측정이 읽는 연결 풀 표본 줄 — 필드 이름이 집계 스크립트의 계약이다(#286 R-3). */
class DbPoolSamplerUnitTest {

    @Test
    fun `풀 상태를 event=db_pool 한 줄로 남긴다`() {
        // given
        val sampler = DbPoolSampler(emptyList(), Duration.ofHours(1))
        val pool = FakePool(active = 7, idle = 3, waiting = 12, total = 10)

        // when
        val lines = CapturedLogs(DbPoolSampler::class).use { logs ->
            sampler.write("wes-api-user", pool)
            logs.eventsOf("db.pool").map { it.formattedMessage }
        }
        sampler.destroy()

        // then
        assertThat(lines).containsExactly("event=db.pool pool=wes-api-user active=7 idle=3 waiting=12 total=10")
    }

    @Test
    fun `첫 연결 전이라 풀이 아직 없으면 아무것도 남기지 않는다`() {
        // given
        val sampler = DbPoolSampler(listOf(HikariDataSource()), Duration.ofHours(1))

        // when
        val lines = CapturedLogs(DbPoolSampler::class).use { logs ->
            sampler.sample()
            logs.eventsOf("db.pool")
        }
        sampler.destroy()

        // then
        assertThat(lines).isEmpty()
    }

    private class FakePool(
        private val active: Int,
        private val idle: Int,
        private val waiting: Int,
        private val total: Int,
    ) : HikariPoolMXBean {
        override fun getIdleConnections() = idle
        override fun getActiveConnections() = active
        override fun getTotalConnections() = total
        override fun getThreadsAwaitingConnection() = waiting
        override fun softEvictConnections() = Unit
        override fun suspendPool() = Unit
        override fun resumePool() = Unit
    }
}
