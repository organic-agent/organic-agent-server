package com.soma.wes.global.cluster

import com.soma.wes.global.config.ClusterLeaderProperties
import com.soma.wes.support.SharedValkey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/** 임대를 Redis 키에 둘 때의 리더 계약(#313). 운영과 같은 엔진(Valkey)에 붙는다. B4에서 Redis 와 함께 지운다. */
class RedisLeaderLeaseTest : LeaderLeaseContract() {

    override val properties = ClusterLeaderProperties(enabled = true, store = ClusterLeaderProperties.Store.REDIS, key = "test:scheduler-leader")

    private val connectionFactory = connect(SharedValkey.mappedPort)
    private val redis = StringRedisTemplate(connectionFactory)
    private val unreachable = connect(port = 1)

    override val store: LeaderLeaseStore = RedisLeaderLeaseStore(redis, properties)

    override fun unreachableStore(): LeaderLeaseStore = RedisLeaderLeaseStore(StringRedisTemplate(unreachable), properties)

    override fun expireLease() {
        redis.delete(properties.key)
    }

    @BeforeEach
    fun clearLease() {
        redis.delete(properties.key)
    }

    @AfterEach
    fun disconnect() {
        connectionFactory.destroy()
        unreachable.destroy()
    }

    private fun connect(port: Int) = LettuceConnectionFactory(RedisStandaloneConfiguration(SharedValkey.host, port)).apply {
        afterPropertiesSet()
        start()
    }
}
