package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

@DisplayName("LocalProcessEmbeddingInvoker")
class LocalProcessEmbeddingInvokerTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `디렉토리가 비어 있거나 embedder 스크립트를 실행할 수 없으면 사용할 수 없다`() {
        assertThat(LocalProcessEmbeddingInvoker(AnalysisProperties()).isAvailable).isFalse()

        File(dir, "embedder.sh").apply { writeText("#!/bin/sh\n"); setExecutable(false) }
        assertThat(LocalProcessEmbeddingInvoker(AnalysisProperties(localScriptDir = dir.path)).isAvailable).isFalse()
    }

    @Test
    fun `갤러리 id와 force 플래그를 넘겨 embedder 스크립트를 띄우고 기다리지 않는다`() {
        // given: 받은 인자를 파일에 적는 가짜 스크립트
        val recorded = File(dir, "args.txt")
        File(dir, "embedder.sh").apply {
            writeText("#!/bin/sh\necho \"$@\" > '${recorded.path}'\n")
            setExecutable(true)
        }
        val invoker = LocalProcessEmbeddingInvoker(AnalysisProperties(localScriptDir = dir.path))
        assertThat(invoker.isAvailable).isTrue()

        // when
        invoker.invoke(galleryId = 42, force = true)

        // then: 비동기라 잠깐 기다려 본다
        waitFor { recorded.exists() }
        assertThat(recorded.readText().trim()).isEqualTo("--gallery-id 42 --force")
    }

    @Test
    fun `프로세스를 못 띄우면 호출 실패 코드다`() {
        val invoker = LocalProcessEmbeddingInvoker(AnalysisProperties(localScriptDir = dir.path))
        assertThatThrownBy { invoker.invoke(galleryId = 1, force = false) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
    }
}
