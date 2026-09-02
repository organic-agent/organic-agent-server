package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.config.CompareProperties
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper

@DisplayName("LocalProcessCompareInvoker")
class LocalProcessCompareInvokerTest {

    @TempDir
    lateinit var dir: File

    // data class 역직렬화에는 Kotlin 모듈이 필요하다 — 운영의 Spring 매퍼와 같은 구성이다.
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `스크립트가 비어 있거나 실행할 수 없으면 사용할 수 없다`() {
        assertThat(invoker(CompareProperties()).isAvailable).isFalse()

        val notExecutable = File(dir, "noexec.sh").apply { writeText("#!/bin/sh\n"); setExecutable(false) }
        assertThat(invoker(CompareProperties(localScript = notExecutable.path)).isAvailable).isFalse()
    }

    @Test
    fun `stdout의 판정 JSON을 파싱해 돌려준다`() {
        // AI CLI의 _response 그대로 — 모르는 키(pipeline, elapsedSeconds …)는 버려야 한다.
        // chosenPhotoId가 문자열인 것도 계약이다(파이썬 쪽 photo id가 str이다).
        val script = fakeScript(
            """
            echo '{"pipeline":"v3","mode":"compare","photoA":"101","photoB":"102",
                   "chosenPhotoId":"101","confidence":"clear","reason":"초점이 더 선명해요.",
                   "source":"llm","cached":false,"elapsedSeconds":5.4}'
            """.trimIndent(),
        )

        val verdict = invoker(CompareProperties(localScript = script.path)).compare(1L, 101L, 102L)

        assertSoftly { softly ->
            softly.assertThat(verdict.chosenPhotoId).isEqualTo(101L)
            softly.assertThat(verdict.confidence).isEqualTo("clear")
            softly.assertThat(verdict.reason).contains("선명")
            softly.assertThat(verdict.source).isEqualTo("llm")
            softly.assertThat(verdict.cached).isFalse()
        }
    }

    @Test
    fun `셀렉 id와 두 사진 id를 인자로 넘긴다`() {
        val recorded = File(dir, "args.txt")
        val script = fakeScript(
            """
            echo "${'$'}@" > '${recorded.path}'
            echo '{"chosenPhotoId":"101","confidence":"slight","reason":"r","source":"template","cached":true}'
            """.trimIndent(),
        )

        val verdict = invoker(CompareProperties(localScript = script.path)).compare(7L, 101L, 102L)

        assertThat(recorded.readText().trim()).isEqualTo("7 101 102")
        assertThat(verdict.cached).isTrue()
    }

    @Test
    fun `프로세스가 실패하면 판정 실패로 번역한다`() {
        val script = fakeScript("echo '설정이 없다' >&2\nexit 1")

        assertThatThrownBy { invoker(CompareProperties(localScript = script.path)).compare(1L, 101L, 102L) }
            .isInstanceOf(RecommendationException::class.java)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.COMPARE_FAILED)
    }

    @Test
    fun `stdout이 JSON이 아니면 판정 실패로 번역한다`() {
        val script = fakeScript("echo '판정이 아니라 안내문이 stdout으로 샜다'")

        assertThatThrownBy { invoker(CompareProperties(localScript = script.path)).compare(1L, 101L, 102L) }
            .isInstanceOf(RecommendationException::class.java)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.COMPARE_FAILED)
    }

    private fun invoker(properties: CompareProperties) = LocalProcessCompareInvoker(properties, objectMapper)

    private fun fakeScript(body: String): File = File(dir, "fake-compare.sh").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }
}
