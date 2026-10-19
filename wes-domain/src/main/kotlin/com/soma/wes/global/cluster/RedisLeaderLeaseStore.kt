package com.soma.wes.global.cluster

import com.soma.wes.global.config.ClusterLeaderProperties
import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * 리더 임대를 Redis 키 하나에 둔다(#313 ADR-0002). 키 값이 임대한 서버의 표식이다. Postgres로 옮긴 뒤(#336 B3) B4에서 지운다.
 *
 * Redis 가 재시작해 키가 통째로 사라지면(영속화 끔) 다른 서버가 바로 얻고, 옛 리더는 다음 갱신까지 믿는다 — 최대 갱신 주기 동안 리더가
 * 둘이다(dev A-2r 실측 3초). 주기 작업이 중복 방지를 DB에 두는 까닭이다(#313 ADR-0002 §7).
 */
@Component
@ConditionalOnExpression("\${app.cluster.leader.enabled:false} and '\${app.cluster.leader.store:redis}'.equalsIgnoreCase('redis')")
class RedisLeaderLeaseStore(
    private val redis: StringRedisTemplate,
    private val properties: ClusterLeaderProperties,
) : LeaderLeaseStore {

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
    }

    override fun claim(holder: String, ttl: Duration): Boolean =
        redis.execute(CLAIM, listOf(properties.key), holder, ttl.toMillis().toString()) == 1L

    override fun release(holder: String) {
        redis.execute(RELEASE, listOf(properties.key), holder)
    }
}
