package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.ScoreWorkerDto
import com.soma.wes.analysis.dto.ScoreWorkerState
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.ScoreWorkerPool
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 워커 풀 — EC2 대신 `scripts/gpu/score-worker.sh`를 서브프로세스로 한 번 띄운다(AI repo `score worker --gpu --no-idle-stop`, 큐를 비우고 유휴 30초 뒤 종료).
 * 인스턴스는 하나("local")이고, 프로세스가 살아 있으면 RUNNING, 끝났으면 STOPPED다. 끄기는 프로세스 종료다.
 */
@Component
@Profile("local")
class LocalProcessScoreWorkerPool(
    private val properties: AnalysisProperties,
    private val clock: Clock,
) : ScoreWorkerPool {

    private val log = LoggerFactory.getLogger(javaClass)

    private val script: File
        get() = File(properties.localScriptDir, "../gpu/$SCRIPT_NAME")

    @Volatile
    private var process: Process? = null

    @Volatile
    private var launchedAt: ZonedDateTime? = null

    override val isAvailable: Boolean
        get() = properties.gpu.enabled && properties.isLocalConfigured && script.canExecute()

    override fun snapshot(): List<ScoreWorkerDto> {
        val alive = process?.isAlive == true
        return listOf(
            ScoreWorkerDto(
                instanceId = INSTANCE_ID,
                state = if (alive) ScoreWorkerState.RUNNING else ScoreWorkerState.STOPPED,
                launchedAt = launchedAt,
            ),
        )
    }

    override fun start() {
        if (process?.isAlive == true) return
        val logFile = File(System.getProperty("java.io.tmpdir"), "wes-gpu-score-worker.log")
        process = try {
            ProcessBuilder(script.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        } catch (e: IOException) {
            log.error("로컬 score 워커 시작 실패: script={}", script, e)
            throw AnalysisException(AnalysisErrorCode.SCORE_WORKER_CONTROL_FAILED)
        }
        launchedAt = ZonedDateTime.now(clock)
        log.info("로컬 score 워커 시작: pid={}, log={}", process?.pid(), logFile)
    }

    override fun stop(instanceId: String) {
        process?.takeIf { it.isAlive }?.let {
            it.destroy()
            log.info("로컬 score 워커 종료: pid={}", it.pid())
        }
    }

    companion object {
        const val INSTANCE_ID = "local"
        /** `scripts/lambda`(Lambda 대역) 옆의 `scripts/gpu`. 운영의 EC2 워커 한 대에 해당한다. */
        const val SCRIPT_NAME = "score-worker.sh"
    }
}
