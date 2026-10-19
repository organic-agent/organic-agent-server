package com.soma.wes.global.cluster

import com.soma.wes.global.config.ClusterLeaderProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

/**
 * 임대 하나를 쥔 서버가 주기 작업 리더다(#313 ADR-0002). 임대는 [LeaderLeaseStore] — Postgres 행 하나(#336 ADR-0002)에 둔다.
 *
 * - [ClusterLeaderProperties.renewInterval]마다 자기 스레드에서 임대를 얻거나 늘린다. 스케줄러 스레드를 쓰지 않는 까닭: 분석 스윕이
 *   스레드를 오래 쥐면 갱신이 밀려 멀쩡한 리더가 임대를 놓친다.
 * - 리더라고 믿는 것은 마지막으로 임대를 얻거나 늘리기 **직전** 시각 + ttl − [SAFETY_MARGIN]까지다. 다른 서버는 임대가 끝나거나
 *   반납된 뒤에야 얻으므로, 갱신이 끊기기만 해서는 두 서버가 동시에 리더라고 믿지 않는다.
 * - 그래도 주기 작업은 중복 방지를 리더 하나에 기대지 않고 DB(조건부 UPDATE·SKIP LOCKED·행 잠금)나 멱등으로 지킨다(#313 ADR-0002 §7).
 * - 임대를 두는 곳이 안 되면 리더를 새로 못 정한다 — 주기 작업이 멈췄다가 돌아오면 이어진다. 사용자 요청은 이 클래스와 무관하다.
 * - 내려갈 때 임대를 돌려준다 — 배포 때 다음 서버가 ttl 을 기다리지 않고 바로 이어받는다.
 */
@Component
@ConditionalOnProperty(prefix = "app.cluster.leader", name = ["enabled"], havingValue = "true")
class LeasedSchedulerLeadership(
    private val store: LeaderLeaseStore,
    private val properties: ClusterLeaderProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : SchedulerLeadership, SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 서버의 임대 표식. 임대 주인이 이것일 때만 늘리고 돌려준다 — 남의 임대를 늘리거나 지우지 않게. */
    private val token = "${System.getenv(HOSTNAME_ENV) ?: UNKNOWN_HOST}-${UUID.randomUUID()}"

    /** 이 시각 전까지 리더라고 믿는다. */
    @Volatile
    private var leaderUntil: Instant = Instant.MIN

    /**
     * 얻었다고 알린 뒤 아직 끝났다고 알리지 않았다. 임대를 두는 곳에 닿지 못해 [leaderUntil]이 저절로 지나면 [tick]의 잃음 분기를 타지
     * 않아, 이것 없이는 마지막 로그가 `leader.acquired`로 남는다 — 10-19 RDS 복구 뒤 두 서버 모두 리더처럼 보였다(#336 08 F6).
     */
    @Volatile
    private var announced = false

    private var executor: ScheduledExecutorService? = null

    /** 내려가는 중이다. 이미 돌던 갱신이 반납 뒤에 임대를 다시 얻지 않게 한다. */
    @Volatile
    private var stopping = false

    companion object {
        /** 임대가 끝나기 이만큼 전부터 리더가 아니라고 본다 — 갱신 왕복·시계 어긋남 몫. */
        private val SAFETY_MARGIN: Duration = Duration.ofSeconds(2)

        private const val THREAD_NAME = "scheduler-leader"

        /** 컨테이너 안에서는 컨테이너 id 다. 로그에서 어느 서버가 리더인지 보이게 표식 앞에 붙인다. */
        private const val HOSTNAME_ENV = "HOSTNAME"
        private const val UNKNOWN_HOST = "wes"
    }

    override fun isLeader(): Boolean = clock.instant().isBefore(leaderUntil)

    override fun start() {
        stopping = false
        executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, THREAD_NAME).apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay(::tick, 0, properties.renewInterval.toMillis(), TimeUnit.MILLISECONDS)
        }
        log.info("event=leader.started token={} ttl={} renew={}", token, properties.ttl, properties.renewInterval)
    }

    /**
     * 돌던 갱신이 끝나기를 기다린 뒤 반납한다. 기다리지 않으면 갱신 도중에 반납한 빈 키를 그 갱신이 다시 얻어, 내려가는 서버가 ttl 동안 임대를
     * 쥐고 다음 서버가 이어받지 못한다(PR #315 리뷰).
     */
    override fun stop() {
        stopping = true
        executor?.let { running ->
            running.shutdown()
            if (!running.awaitTermination(properties.renewInterval.toMillis(), TimeUnit.MILLISECONDS)) running.shutdownNow()
        }
        executor = null
        release()
    }

    override fun isRunning(): Boolean = executor != null

    /** 임대를 한 번 얻거나 늘린다. 리더가 되면 [SchedulerLeadershipAcquiredEvent]를 낸다. */
    fun tick() {
        if (stopping) return
        val attemptedAt = clock.instant()
        val wasLeader = isLeader()
        if (announced && !wasLeader) {
            log.warn("event=leader.expired token={} — 갱신하지 못한 채 임대 시각이 지났다", token)
            announced = false
        }
        val held = try {
            claim()
        } catch (e: RuntimeException) {
            // 지금까지의 임대 시각은 그대로 둔다 — 그 안이면 임대는 아직 내 것이고, 지나면 isLeader 가 스스로 거짓이 된다.
            log.warn("event=leader.unreachable was_leader={} error={} — 다음 갱신에서 다시 본다", wasLeader, e.toString())
            return
        }

        if (!held) {
            if (wasLeader) log.warn("event=leader.lost token={}", token)
            leaderUntil = Instant.MIN
            announced = false
            return
        }

        if (stopping) return
        leaderUntil = attemptedAt.plus(properties.ttl).minus(SAFETY_MARGIN)
        if (!wasLeader) {
            announced = true
            log.info("event=leader.acquired token={}", token)
            events.publishEvent(SchedulerLeadershipAcquiredEvent())
        }
    }

    private fun claim(): Boolean = store.claim(token, properties.ttl)

    private fun release() {
        val wasLeader = isLeader()
        leaderUntil = Instant.MIN
        announced = false
        if (!wasLeader) return
        try {
            store.release(token)
            log.info("event=leader.released token={}", token)
        } catch (e: RuntimeException) {
            log.warn("event=leader.release_failed — ttl 뒤 다른 서버가 이어받는다", e)
        }
    }
}
