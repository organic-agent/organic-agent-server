package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.config.CompareProperties
import com.soma.wes.recommendation.dto.PairVerdictDto
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.service.PairCompareInvoker
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * 로컬 프로필의 비교샷 실행기 — 경량 Lambda 대신 `scripts/local-compare.sh`를 서브프로세스로
 * 띄우고 **끝나기를 기다린다**. [LocalProcessEmbeddingInvoker]와 달리 동기다 — 결과가 DB가 아니라
 * 응답으로 와야 하기 때문이다.
 *
 * 스크립트의 stdout에는 판정 JSON만 나오는 계약이다(안내·오류는 전부 stderr →
 * `/tmp/wes-compare-<selectionId>.log`). venv·DB·버킷을 스크립트가 정하므로 이 클래스는
 * 파이썬을 모른다.
 */
@Component
@Profile("local")
class LocalProcessCompareInvoker(
    private val properties: CompareProperties,
    private val objectMapper: ObjectMapper,
) : PairCompareInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isAvailable: Boolean
        get() = properties.isLocalConfigured && File(properties.localScript).canExecute()

    override fun compare(selectionId: Long, photoA: Long, photoB: Long): PairVerdictDto {
        val command = listOf(
            File(properties.localScript).absolutePath,
            selectionId.toString(),
            photoA.toString(),
            photoB.toString(),
        )
        val logFile = File(System.getProperty("java.io.tmpdir"), "wes-compare-$selectionId.log")

        val process = try {
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
        } catch (e: IOException) {
            log.error("비교샷 프로세스 시작 실패: selectionId={}, script={}", selectionId, properties.localScript, e)
            throw RecommendationException(RecommendationErrorCode.COMPARE_FAILED)
        }

        // stdout을 다 읽기 전에 waitFor로 기다려도 안전한 근거: 판정 JSON은 1KB 안팎이라 파이프
        // 버퍼(64KB)를 넘지 않는다. 시간 예산은 판정 8초 + 파이썬 기동 + 최초 1회 editable 설치.
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            log.error("비교샷 프로세스 타임아웃({}초): selectionId={}, log={}", TIMEOUT_SECONDS, selectionId, logFile)
            throw RecommendationException(RecommendationErrorCode.COMPARE_FAILED)
        }
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.exitValue() != 0) {
            log.error(
                "비교샷 프로세스 실패: selectionId={}, exit={}, log={}",
                selectionId, process.exitValue(), logFile,
            )
            throw RecommendationException(RecommendationErrorCode.COMPARE_FAILED)
        }

        return try {
            objectMapper.readValue(stdout, PairVerdictDto::class.java)
        } catch (e: JacksonException) {
            log.error("비교샷 stdout이 계약을 벗어남: selectionId={}, stdout={}, log={}", selectionId, stdout, logFile, e)
            throw RecommendationException(RecommendationErrorCode.COMPARE_FAILED)
        }
    }

    companion object {

        /** 판정 예산 8초(AI 쪽) + 파이썬 기동·미리보기 다운로드 + 최초 1회 venv 설치 여유. */
        const val TIMEOUT_SECONDS = 60L
    }
}
