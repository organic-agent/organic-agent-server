package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.StageCallDto
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
    fun `디렉토리가 비어 있거나 그 호출의 스크립트가 없으면 사용할 수 없다`() {
        val unconfigured = LocalProcessStageInvoker(AnalysisProperties())
        assertThat(listOf(StageCallDto.Embed::class, StageCallDto.Score::class, StageCallDto.Categorize::class).none { unconfigured.isAvailable(it) }).isTrue()

        fakeScript("score.sh")
        val invoker = LocalProcessStageInvoker(AnalysisProperties(localScriptDir = dir.path))
        assertSoftly { softly ->
            softly.assertThat(invoker.isAvailable(StageCallDto.Score::class)).isTrue()
            softly.assertThat(invoker.isAvailable(StageCallDto.Embed::class)).isFalse()
            softly.assertThat(invoker.isAvailable(StageCallDto.Categorize::class)).isFalse()
        }
    }

    @Test
    fun `호출과 같은 이름의 스크립트를 Lambda 페이로드 키 인자로 띄우고 기다리지 않는다`() {
        // given: 받은 인자를 파일에 적는 가짜 스크립트 — 함수 이름은 AI repo 모듈과 같다(embedder·score·categorize)
        val embedder = fakeScript("embedder.sh")
        val categorize = fakeScript("categorize.sh")
        val invoker = LocalProcessStageInvoker(AnalysisProperties(localScriptDir = dir.path))

        // when
        invoker.invoke(StageCallDto.Embed(galleryId = 42, photoIds = listOf(1, 2, 3)))
        invoker.invoke(StageCallDto.Categorize(galleryId = 42, jobId = 9))

        // then: 비동기라 잠깐 기다려 본다
        waitFor { embedder.exists() && categorize.exists() }
        assertThat(embedder.readText().trim()).isEqualTo("--gallery-id 42 --photo-ids 1,2,3")
        assertThat(categorize.readText().trim()).isEqualTo("--gallery-id 42 --job-id 9")
    }

    @Test
    fun `exact photo 는 embedder 스크립트가 있어도 지원하지 않는다`() {
        fakeScript("embedder.sh")
        val invoker = LocalProcessStageInvoker(AnalysisProperties(localScriptDir = dir.path))
        val call = StageCallDto.ExactPhoto(jobId = 1, attemptCount = 1, jobType = "EMBEDDING", photoId = 2, galleryId = 3, storageKey = "k", revisionId = 4)

        assertThat(invoker.isAvailable(StageCallDto.Embed::class)).isTrue()
        assertThat(invoker.isAvailable(StageCallDto.ExactPhoto::class)).isFalse()
        assertThatThrownBy { invoker.invoke(call) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }

    @Test
    fun `프로세스를 못 띄우면 호출 실패 코드다`() {
        val invoker = LocalProcessStageInvoker(AnalysisProperties(localScriptDir = dir.path))
        assertThatThrownBy { invoker.invoke(StageCallDto.Categorize(galleryId = 1, jobId = 1)) }
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
