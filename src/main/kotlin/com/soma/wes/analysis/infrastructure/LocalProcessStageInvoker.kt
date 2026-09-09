package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.StageCallDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.StageInvoker
import java.io.File
import java.io.IOException
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 실행기 — Lambda 함수 하나가 `scripts/lambda/<함수>.sh` 하나다. 이름은 AI repo 최상위 모듈(embedder·score·
 * categorize)과 같고, 인자는 Lambda 페이로드 키를 그대로 옮긴 것이다(`--gallery-id`, `--photo-ids`, `--job-id`).
 * 운영의 EVENT 호출과 같은 의미다: 프로세스를 시작만 하고 기다리지 않는다. 표준 출력은 `/tmp/wes-lambda-<함수>-<galleryId>.log`.
 */
@Component
@Profile("local")
class LocalProcessStageInvoker(
    private val properties: AnalysisProperties,
) : StageInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(call: KClass<out StageCallDto>): Boolean = properties.isLocalConfigured && scriptOf(call).canExecute()

    override fun invoke(call: StageCallDto) {
        val script = scriptOf(call::class)
        val command = buildList {
            add(script.absolutePath)
            add("--gallery-id")
            add(call.galleryId.toString())
            when (call) {
                is StageCallDto.Embed -> { add("--photo-ids"); add(call.photoIds.joinToString(",")) }
                is StageCallDto.Score -> { add("--photo-ids"); add(call.photoIds.joinToString(",")) }
                is StageCallDto.Categorize -> { add("--job-id"); add(call.jobId.toString()) }
            }
        }
        val logFile = File(System.getProperty("java.io.tmpdir"), "wes-lambda-${script.nameWithoutExtension}-${call.galleryId}.log")
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        } catch (e: IOException) {
            log.error("로컬 Lambda 대역 시작 실패: call={}, script={}", call, script, e)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        log.info("로컬 Lambda 대역 시작: script={}, galleryId={}, pid={}, log={}", script.name, call.galleryId, process.pid(), logFile)
    }

    private fun scriptOf(call: KClass<out StageCallDto>): File = File(properties.localScriptDir, "${AnalysisProperties.localFunctionOf(call)}.sh")
}
