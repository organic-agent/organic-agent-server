package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.EmbeddingProperties
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalProcessStageInvokerUnitTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `스크립트가 없으면 어느 단계도 사용할 수 없다`() {
        val invoker = LocalProcessStageInvoker(EmbeddingProperties(functionName = ""))
        assertThat(AnalysisStage.entries.none { invoker.isAvailable(it) }).isTrue()
    }

    @Test
    fun `갤러리 id·단계·잡 id·force 플래그를 넘겨 스크립트를 띄우고 기다리지 않는다`() {
        // given: 받은 인자를 파일에 적는 가짜 스크립트
        val recorded = File(dir, "args.txt")
        val script = File(dir, "fake-ai.sh").apply {
            writeText("#!/bin/sh\necho \"$@\" > '${recorded.path}'\n")
            setExecutable(true)
        }
        val invoker = LocalProcessStageInvoker(EmbeddingProperties(functionName = "", localScript = script.path))
        assertThat(invoker.isAvailable(AnalysisStage.SCORE)).isTrue()

        // when
        invoker.invoke(AnalysisStage.SCORE, jobId = 9, galleryId = 42, force = true)

        // then: 비동기라 잠깐 기다려 본다
        waitFor { recorded.exists() }
        assertThat(recorded.readText().trim()).isEqualTo("42 --stage score --job-id 9 --force")
    }

    @Test
    fun `프로세스를 못 띄우면 호출 실패 코드다`() {
        val missing = File(dir, "missing.sh")
        val invoker = LocalProcessStageInvoker(EmbeddingProperties(functionName = "", localScript = missing.path))
        assertThatThrownBy { invoker.invoke(AnalysisStage.EMBED, jobId = 1, galleryId = 1, force = false) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
    }
}
