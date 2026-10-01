package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.LocalProcessProperties
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
 * categorize)과 같고, 인자는 Lambda 페이로드 키를 그대로 옮긴 것이다(`--gallery-id`, `--photo-ids`, `--job-id`, `--concept-count`).
 * 운영의 EVENT 호출과 같은 의미다: 프로세스를 시작만 하고 기다리지 않는다. 표준 출력은 `/tmp/wes-lambda-<함수>-<galleryId>.log`.
 *
 * [AiTaskDto.ExactPhoto](관리자 사진 교체)는 지원하지 않는다 — `embedder.sh`는 갤러리 배정(`--photo-ids`)만 받는다. "없음"을
 * 정직하게 알려 관리자 잡이 `PHOTO_PROCESSING_NOT_CONFIGURED`로 닫히게 한다. 조용히 삼키면 잡이 영원히 대기한다.
 *
 * 함수마다 프로세스는 하나만 띄운다 — torch 프로세스 하나가 2~4GB라 겹치면 노트북 메모리를 넘긴다. 앞 프로세스의 pid를
 * `wes-lambda-<함수>.pid`에 적어 두고(wes를 다시 띄워도 남는다) 살아 있으면 새로 띄우지 않는다. 임베더는 전송 실패로 알려
 * 배정과 시도 수를 되돌리게 하고, score·categorize는 조용히 넘긴다 — 부르는 쪽의 간격·타임아웃이 다시 보낸다.
 */
@Component
@Profile("local")
class LocalAiTaskSender(
    private val properties: LocalProcessProperties,
) : AiTaskSender {

    private val log = LoggerFactory.getLogger(javaClass)

    /** pid 파일을 두는 곳. 테스트가 바꾼다. */
    var pidDir: File = File(System.getProperty("java.io.tmpdir"))

    override fun isAvailable(task: KClass<out AiTaskDto>): Boolean =
        task != AiTaskDto.ExactPhoto::class && properties.isConfigured && scriptOf(task).canExecute()

    /** 확인과 띄우기를 한 덩어리로 — 두 스레드가 같은 순간에 들어오면 둘 다 "앞 프로세스 없음"으로 보고 같이 띄운다. */
    @Synchronized
    override fun send(task: AiTaskDto) {
        if (task is AiTaskDto.ExactPhoto) {
            log.error("로컬 프로필은 사진 단위 재처리를 지원하지 않는다: jobId={}, photoId={}", task.jobId, task.photoId)
            throw AnalysisException(AnalysisErrorCode.AI_TASK_SEND_FAILED)
        }
        val script = scriptOf(task::class)
        val pidFile = File(pidDir, "wes-lambda-${script.nameWithoutExtension}.pid")
        runningPid(pidFile)?.let { pid ->
            log.info("로컬 Lambda 대역이 아직 돈다 — 새로 띄우지 않는다: script={}, galleryId={}, pid={}", script.name, task.galleryId, pid)
            if (task is AiTaskDto.Embed) throw AnalysisException(AnalysisErrorCode.AI_TASK_SEND_FAILED)
            return
        }
        val command = buildList {
            add(script.absolutePath)
            add("--gallery-id")
            add(task.galleryId.toString())
            when (task) {
                is AiTaskDto.Embed -> { add("--photo-ids"); add(task.photoIds.joinToString(",")) }
                is AiTaskDto.Score -> { add("--photo-ids"); add(task.photoIds.joinToString(",")) }
                is AiTaskDto.Categorize -> {
                    add("--job-id"); add(task.jobId.toString())
                    task.conceptCount?.let { add("--concept-count"); add(it.toString()) }
                }
                is AiTaskDto.ExactPhoto -> error("위에서 거른 작업: $task")
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
        pidFile.writeText("${process.pid()} ${startMillisOf(process.toHandle())}")
        log.info("로컬 Lambda 대역 시작: script={}, galleryId={}, pid={}, log={}", script.name, task.galleryId, process.pid(), logFile)
    }

    /** pid 파일이 가리키는 프로세스가 살아 있으면 그 pid. 시작 시각까지 맞춰 보아 재부팅 뒤 다른 프로세스가 같은 pid를 쓰는 경우를 거른다. */
    private fun runningPid(pidFile: File): Long? {
        if (!pidFile.isFile) return null
        val parts = pidFile.readText().trim().split(" ")
        val pid = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        return pid.takeIf { handle.isAlive && startMillisOf(handle) == parts.getOrNull(1)?.toLongOrNull() }
    }

    private fun startMillisOf(handle: ProcessHandle): Long? = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null)

    private fun scriptOf(task: KClass<out AiTaskDto>): File = File(properties.localScriptDir, "${scriptNameOf(task)}.sh")

    /** AI 작업 → 대역 스크립트 이름. 운영 Lambda 함수·AI repo 모듈과 같은 이름이라 셋을 나란히 읽을 수 있다. */
    private fun scriptNameOf(task: KClass<out AiTaskDto>): String = when (task) {
        AiTaskDto.Embed::class -> "embedder"
        AiTaskDto.Score::class -> "score"
        AiTaskDto.Categorize::class -> "categorize"
        else -> error("로컬 대역이 없는 AI 작업: $task")
    }
}
