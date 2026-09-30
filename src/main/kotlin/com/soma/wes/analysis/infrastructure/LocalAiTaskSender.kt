package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.AiTaskSender
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
 *
 * [AiTaskDto.ExactPhoto](관리자 사진 교체)는 지원하지 않는다 — `embedder.sh`는 갤러리 배정(`--photo-ids`)만 받는다. "없음"을
 * 정직하게 알려 관리자 잡이 `PHOTO_PROCESSING_NOT_CONFIGURED`로 닫히게 한다. 조용히 삼키면 잡이 영원히 대기한다.
 */
@Component
@Profile("local")
class LocalAiTaskSender(
    private val properties: AnalysisProperties,
) : AiTaskSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(task: KClass<out AiTaskDto>): Boolean =
        task != AiTaskDto.ExactPhoto::class && properties.isLocalConfigured && scriptOf(task).canExecute()

    override fun send(task: AiTaskDto) {
        if (task is AiTaskDto.ExactPhoto) {
            log.error("로컬 프로필은 사진 단위 재처리를 지원하지 않는다: jobId={}, photoId={}", task.jobId, task.photoId)
            throw AnalysisException(AnalysisErrorCode.AI_TASK_SEND_FAILED)
        }
        val script = scriptOf(task::class)
        val command = buildList {
            add(script.absolutePath)
            add("--gallery-id")
            add(task.galleryId.toString())
            when (task) {
                is AiTaskDto.Embed -> { add("--photo-ids"); add(task.photoIds.joinToString(",")) }
                is AiTaskDto.Score -> { add("--photo-ids"); add(task.photoIds.joinToString(",")) }
                is AiTaskDto.Categorize -> { add("--job-id"); add(task.jobId.toString()) }
                is AiTaskDto.ExactPhoto -> error("위에서 거른 호출: $task")
            }
        }
        val logFile = File(System.getProperty("java.io.tmpdir"), "wes-lambda-${script.nameWithoutExtension}-${task.galleryId}.log")
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        } catch (e: IOException) {
            log.error("로컬 Lambda 대역 시작 실패: task={}, script={}", task, script, e)
            throw AnalysisException(AnalysisErrorCode.AI_TASK_SEND_FAILED)
        }
        log.info("로컬 Lambda 대역 시작: script={}, galleryId={}, pid={}, log={}", script.name, task.galleryId, process.pid(), logFile)
    }

    private fun scriptOf(task: KClass<out AiTaskDto>): File = File(properties.localScriptDir, "${AnalysisProperties.localFunctionOf(task)}.sh")
}
