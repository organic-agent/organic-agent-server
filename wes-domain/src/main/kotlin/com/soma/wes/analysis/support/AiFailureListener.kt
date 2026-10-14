package com.soma.wes.analysis.support

import com.soma.wes.analysis.dto.AiFailureMessageDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.AiFailureService
import com.soma.wes.analysis.service.port.AiFailureQueue
import com.soma.wes.global.config.AnalysisPoolRoute
import com.soma.wes.global.logging.LogContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

/**
 * 분석 Lambda 실패 큐를 읽는 전용 스레드(#286 (c) ②). 롱 폴링이라 메시지가 오면 곧바로 깨어나고, 없으면 기다리다 빈손으로 돌아와 다시 읽는다.
 * 스케줄러 스레드를 쓰지 않는다 — 기다리는 동안 분석 스윕·하트비트를 붙잡지 않게.
 *
 * 메시지 하나는 [AiFailureService]가 처리한 뒤에 지운다. 처리 중 예외가 나면 지우지 않는다 — 가시성 시간(60초) 뒤 다시 보여 다시 처리한다.
 * 읽을 수 없는 메시지는 기록만 하고 지운다(되풀이해도 읽을 수 없다). 큐 자체를 못 읽으면 잠시 쉬었다 다시 읽고, 그동안 재전송은 시간 기반
 * 안전망이 맡는다. 큐 주소(`app.analysis.failure-queue.queue-url`)가 없으면 뜨지 않는다.
 */
@Component
@ConditionalOnExpression("!'\${app.analysis.failure-queue.queue-url:}'.isBlank()")
class AiFailureListener(
    private val queue: AiFailureQueue,
    private val aiFailureService: AiFailureService,
    private val analysisPoolRoute: AnalysisPoolRoute,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false

    private var thread: Thread? = null

    override fun start() {
        running = true
        thread = Thread(::listen, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
        log.info("event=ai_failure.listening")
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(STOP_WAIT_MILLIS)
    }

    override fun isRunning(): Boolean = running

    private fun listen() {
        while (running) {
            try {
                pollOnce()
            } catch (e: AnalysisException) {
                if (running) pause("failure queue unavailable — {}초 뒤 다시 읽는다", e)
            } catch (e: RuntimeException) {
                if (running) pause("failure queue poll failed — {}초 뒤 다시 읽는다", e)
            }
        }
    }

    /** 한 번 읽고 받은 메시지를 처리한다. 처리해 지운 메시지 수를 돌려준다. */
    fun pollOnce(): Int = queue.receive().count { message -> LogContext.sweep { analysisPoolRoute.asAnalysis { handle(message) } } }

    private fun handle(message: AiFailureMessageDto): Boolean {
        val failure = message.failure
        if (failure != null) {
            try {
                aiFailureService.handle(failure)
            } catch (e: RuntimeException) {
                log.error("실패 메시지 처리 실패 — 지우지 않고 가시성 시간 뒤 다시 처리한다: function={}", failure.functionName, e)
                return false
            }
        } else {
            log.warn("event=ai.failed action=unreadable — 읽을 수 없는 메시지를 지운다")
        }
        queue.delete(message.receiptHandle)
        return true
    }

    private fun pause(message: String, e: RuntimeException) {
        log.warn(message, ERROR_PAUSE_MILLIS / 1000, e)
        try {
            Thread.sleep(ERROR_PAUSE_MILLIS)
        } catch (_: InterruptedException) {
            // stop()이 깨운 것이다 — 플래그를 되살리지 않는다. 되살리면 다음 읽기·쉬기가 곧바로 끊겨 헛돈다. 루프가 running 을 보고 끝난다.
        }
    }

    companion object {
        private const val THREAD_NAME = "ai-failure-listener"

        /** 큐를 못 읽었을 때 쉬는 시간. 권한·네트워크 문제가 이어져도 로그가 초당 쏟아지지 않게. */
        private const val ERROR_PAUSE_MILLIS = 5_000L

        /** 종료 때 읽던 요청이 끝나기를 기다리는 시간(롱 폴링 10초 + 여유). */
        private const val STOP_WAIT_MILLIS = 15_000L
    }
}
