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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * Redis 키 하나를 임대해 주기 작업 리더를 정한다(#313 ADR-0002). 키 값이 이 서버의 표식이면 리더다.
 *
 * - [ClusterLeaderProperties.renewInterval]마다 자기 스레드에서 임대를 얻거나 늘린다. 스케줄러 스레드를 쓰지 않는 까닭: 분석 스윕이
 *   스레드를 오래 쥐면 갱신이 밀려 멀쩡한 리더가 임대를 놓친다.
 * - 리더라고 믿는 것은 마지막으로 임대를 얻거나 늘리기 **직전** 시각 + ttl − [SAFETY_MARGIN]까지다. 다른 서버는 키가 사라진 뒤에야
 *   얻으므로, 갱신이 끊겨도 두 서버가 동시에 리더라고 믿는 때가 없다.
 * - Redis 가 안 되면 리더를 새로 못 정한다 — 주기 작업이 멈췄다가 Redis 가 돌아오면 이어진다(ADR-0002가 감수한 것). 사용자 요청은
 *   이 클래스와 무관하다.
 * - 내려갈 때 임대를 돌려준다 — 배포 때 다음 서버가 ttl 을 기다리지 않고 바로 이어받는다.
 */
@Component
@ConditionalOnProperty(prefix = "app.cluster.leader", name = ["enabled"], havingValue = "true")
class RedisSchedulerLeadership(
    private val redis: StringRedisTemplate,
    private val properties: ClusterLeaderProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : SchedulerLeadership, SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 서버의 임대 표식. 키 값이 이것과 같을 때만 늘리고 돌려준다 — 남의 임대를 늘리거나 지우지 않게. */
    private val token = "${System.getenv(HOSTNAME_ENV) ?: UNKNOWN_HOST}-${UUID.randomUUID()}"

    /** 이 시각 전까지 리더라고 믿는다. */
    @Volatile
    private var leaderUntil: Instant = Instant.MIN

    private var executor: ScheduledExecutorService? = null

    /** 내려가는 중이다. 이미 돌던 갱신이 반납 뒤에 임대를 다시 얻지 않게 한다. */
    @Volatile
    private var stopping = false

    companion object {
        /** 내 임대면 늘리고, 빈 키면 얻는다. 남의 임대면 0. 읽고 쓰는 사이에 다른 서버가 끼지 않게 한 스크립트로 한다. */
        private val CLAIM = DefaultRedisScript(
            """
            local holder = redis.call('get', KEYS[1])
            if holder == ARGV[1] then
              redis.call('pexpire', KEYS[1], ARGV[2])
              return 1
            end
            if not holder then
              redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        /** 내 임대일 때만 지운다. */
        private val RELEASE = DefaultRedisScript(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

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
        val held = try {
            claim()
        } catch (e: RuntimeException) {
            // 지금까지의 임대 시각은 그대로 둔다 — 그 안이면 키는 아직 내 것이고, 지나면 isLeader 가 스스로 거짓이 된다.
            log.warn("event=leader.unreachable was_leader={} — 다음 갱신에서 다시 본다", wasLeader, e)
            return
        }

        if (!held) {
            if (wasLeader) log.warn("event=leader.lost token={}", token)
            leaderUntil = Instant.MIN
            return
        }

        if (stopping) return
        leaderUntil = attemptedAt.plus(properties.ttl).minus(SAFETY_MARGIN)
        if (!wasLeader) {
            log.info("event=leader.acquired token={}", token)
            events.publishEvent(SchedulerLeadershipAcquiredEvent())
        }
    }

    private fun claim(): Boolean =
        redis.execute(CLAIM, listOf(properties.key), token, properties.ttl.toMillis().toString()) == 1L

    private fun release() {
        val wasLeader = isLeader()
        leaderUntil = Instant.MIN
        if (!wasLeader) return
        try {
            redis.execute(RELEASE, listOf(properties.key), token)
            log.info("event=leader.released token={}", token)
        } catch (e: RuntimeException) {
            log.warn("event=leader.release_failed — ttl 뒤 다른 서버가 이어받는다", e)
        }
    }
}
