package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.EmbeddingInvoker
import com.soma.wes.analysis.service.ExactPhotoProcessingRequest
import java.io.File
import java.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 관리자용 임베딩 실행기 — `scripts/lambda/embedder.sh`를 서브프로세스로 띄운다. [LocalProcessStageInvoker]의
 * EMBED 단계와 같은 스크립트이고, 잡 없이 `--gallery-id [--force]`만 넘기는 것이 다르다(관리자 갤러리 재처리).
 * 운영에서 Lambda `EVENT` 호출이 "큐에 넣고 즉시 돌아오는" 것과 같은 의미다: 프로세스를 시작만 하고 기다리지 않는다.
 */
@Component
@Profile("local")
class LocalProcessEmbeddingInvoker(
    private val properties: AnalysisProperties,
) : EmbeddingInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    private val script: File
        get() = File(properties.localScriptDir, "${AnalysisProperties.localFunctionOf(AnalysisStage.EMBED)}.sh")

    override val isAvailable: Boolean
        get() = properties.isLocalConfigured && script.canExecute()

    override fun invoke(galleryId: Long, force: Boolean) {
        val command = buildList {
            add(script.absolutePath)
            add("--gallery-id")
            add(galleryId.toString())
            if (force) add("--force")
        }
        val logFile = File(System.getProperty("java.io.tmpdir"), "wes-lambda-embedder-$galleryId.log")
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        } catch (e: IOException) {
            // Lambda 어댑터의 SdkException과 같은 자리 — 계산 실패가 아니라 실행기를 못 띄운 것이다.
            log.error("로컬 임베딩 프로세스 시작 실패: galleryId={}, script={}", galleryId, script, e)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        log.info("로컬 임베딩 프로세스 시작: galleryId={}, pid={}, log={}", galleryId, process.pid(), logFile)
    }

    /** 관리자 사진 교체 재처리는 로컬 스크립트가 지원하지 않는다 — 조용히 삼키면 잡이 영원히 대기하므로 실패를 알린다. */
    override fun invoke(request: ExactPhotoProcessingRequest) {
        log.error("로컬 프로필은 사진 단위 재처리를 지원하지 않는다: jobId={}, photoId={}", request.jobId, request.photoId)
        throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }
}
