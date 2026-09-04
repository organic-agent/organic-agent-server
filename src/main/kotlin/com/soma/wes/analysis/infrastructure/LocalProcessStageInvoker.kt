package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.EmbeddingProperties
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.StageInvoker
import java.io.File
import java.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 실행기 — Lambda 대신 노트북에서 `scripts/local-ai.sh <galleryId> --stage <embed|score|categorize> --job-id J`를
 * 서브프로세스로 띄운다. 운영의 EVENT 호출과 같은 의미다: 프로세스를 시작만 하고 기다리지 않는다.
 * 스크립트가 부르는 AI repo CLI는 Lambda와 같은 코드로 잡 행을 쓴다. 표준 출력은 `/tmp/wes-analysis-<stage>-<galleryId>.log`.
 */
@Component
@Profile("local")
class LocalProcessStageInvoker(
    private val properties: EmbeddingProperties,
) : StageInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(stage: AnalysisStage): Boolean =
        properties.isLocalConfigured && File(properties.localScript).canExecute()

    override fun invoke(stage: AnalysisStage, jobId: Long, galleryId: Long, force: Boolean) {
        val stageName = stage.name.lowercase()
        val command = buildList {
            add(File(properties.localScript).absolutePath)
            add(galleryId.toString())
            add("--stage")
            add(stageName)
            add("--job-id")
            add(jobId.toString())
            if (force) add("--force")
        }
        val logFile = File(System.getProperty("java.io.tmpdir"), "wes-analysis-$stageName-$galleryId.log")
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        } catch (e: IOException) {
            log.error("로컬 분석 프로세스 시작 실패: stage={}, jobId={}, script={}", stage, jobId, properties.localScript, e)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        log.info("로컬 분석 프로세스 시작: stage={}, jobId={}, galleryId={}, pid={}, log={}", stage, jobId, galleryId, process.pid(), logFile)
    }
}
