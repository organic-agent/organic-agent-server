package com.soma.wes.global.logging

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 앱 연결 풀(Hikari)의 표본을 [interval]마다 한 줄 남긴다 — `event=db.pool active idle waiting total`.
 * 요청이 풀 앞에서 줄 서는지(waiting > 0) 보려는 부하 측정용이라 기본은 꺼져 있고, 측정 때만 켠다(#286 R-3).
 *
 * 스케줄러 풀(`spring.task.scheduling`)이 아니라 자기 스레드 하나로 돈다. 분석 스윕이 스케줄러 스레드를 다 잡고 있어도
 * 표본이 밀리지 않아야 그 순간의 풀 상태가 보인다. DB를 읽지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "app.observability.db-pool", name = ["enabled"], havingValue = "true")
class DbPoolSampler(
    private val dataSource: DataSource,
    @Value("\${app.observability.db-pool.interval:PT5S}") interval: Duration,
) : DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "db-pool-sampler").apply { isDaemon = true }
    }

    init {
        executor.scheduleAtFixedRate({ sampleSafely() }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** `scheduleAtFixedRate`는 작업이 한 번 던지면 이후 주기를 모두 취소한다 — 표본이 조용히 끊기면 "대기 없음"으로 잘못 읽힌다. */
    private fun sampleSafely() {
        try {
            sample()
        } catch (e: Exception) {
            log.warn("event=db.pool.failed reason={}", e.toString())
        }
    }

    fun sample() {
        // 풀은 첫 연결 때 만들어진다 — 그 전에는 표본이 없다.
        val pool = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean ?: return
        write(pool)
    }

    fun write(pool: HikariPoolMXBean) {
        log.info(
            "event=db.pool active={} idle={} waiting={} total={}",
            pool.activeConnections, pool.idleConnections, pool.threadsAwaitingConnection, pool.totalConnections,
        )
    }

    override fun destroy() {
        executor.shutdownNow()
    }
}
