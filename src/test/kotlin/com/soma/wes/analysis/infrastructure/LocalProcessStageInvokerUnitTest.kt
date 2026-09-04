package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalProcessStageInvokerUnitTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `디렉토리가 비어 있거나 그 단계의 스크립트가 없으면 사용할 수 없다`() {
        assertThat(AnalysisStage.entries.none { LocalProcessStageInvoker(AnalysisProperties()).isAvailable(it) }).isTrue()

        fakeScript("score.sh")
        val invoker = LocalProcessStageInvoker(AnalysisProperties(localScriptDir = dir.path))
        assertSoftly { softly ->
            softly.assertThat(invoker.isAvailable(AnalysisStage.SCORE)).isTrue()
            softly.assertThat(invoker.isAvailable(AnalysisStage.EMBED)).isFalse()
            softly.assertThat(invoker.isAvailable(AnalysisStage.CATEGORIZE)).isFalse()
        }
    }

    @Test
    fun `단계와 같은 이름의 스크립트를 Lambda 페이로드 키 인자로 띄우고 기다리지 않는다`() {
        // given: 받은 인자를 파일에 적는 가짜 스크립트 — 함수 이름은 AI repo 모듈과 같다(embedder·score·categorize)
        val recorded = fakeScript("embedder.sh")
        val invoker = LocalProcessStageInvoker(AnalysisProperties(localScriptDir = dir.path))

        // when
        invoker.invoke(AnalysisStage.EMBED, jobId = 9, galleryId = 42, force = true)

        // then: 비동기라 잠깐 기다려 본다
        waitFor { recorded.exists() }
        assertThat(recorded.readText().trim()).isEqualTo("--gallery-id 42 --job-id 9 --force")
    }

    @Test
    fun `프로세스를 못 띄우면 호출 실패 코드다`() {
        val invoker = LocalProcessStageInvoker(AnalysisProperties(localScriptDir = dir.path))
        assertThatThrownBy { invoker.invoke(AnalysisStage.CATEGORIZE, jobId = 1, galleryId = 1, force = false) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }

    /** 받은 인자를 적을 파일을 돌려준다. */
    private fun fakeScript(name: String): File {
        val recorded = File(dir, "$name.args")
        File(dir, name).apply {
            writeText("#!/bin/sh\necho \"$@\" > '${recorded.path}'\n")
            setExecutable(true)
        }
        return recorded
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
    }
}
