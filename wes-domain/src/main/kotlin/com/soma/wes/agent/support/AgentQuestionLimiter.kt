package com.soma.wes.agent.support

import com.soma.wes.agent.config.AgentProperties
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 사용자별 질문 상한([AgentProperties]). 지난 창 안의 질문 시각만 서버 메모리에 둔다.
 *
 * 서버마다 따로 센다 — 앱 서버가 2대면 실제 상한은 최대 2배다. 비용 남용을 막는 울타리라 그 정도는 받아들이고,
 * 공유 저장소(Redis)를 들이지 않는다. 재시작하면 처음부터 센다.
 */
@Component
class AgentQuestionLimiter(
    private val properties: AgentProperties,
    private val clock: Clock,
) {

    private val askedAtByUserId = ConcurrentHashMap<Long, ArrayDeque<Instant>>()

    fun acquire(userId: Long) {
        val now = clock.instant()
        val windowStart = now.minus(properties.window)
        val askedAt = askedAtByUserId.computeIfAbsent(userId) { ArrayDeque() }

        synchronized(askedAt) {
            while (askedAt.peekFirst()?.isAfter(windowStart) == false) askedAt.pollFirst()
            if (askedAt.size >= properties.maxQuestions) throw AgentException(AgentErrorCode.TOO_MANY_QUESTIONS)
            askedAt.addLast(now)
        }
    }
}
