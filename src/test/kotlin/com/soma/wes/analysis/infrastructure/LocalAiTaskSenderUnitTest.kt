package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.LocalProcessProperties
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalAiTaskSenderUnitTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `디렉토리가 비어 있거나 그 호출의 스크립트가 없으면 사용할 수 없다`() {
        val unconfigured = LocalAiTaskSender(LocalProcessProperties())
        assertThat(listOf(AiTaskDto.Embed::class, AiTaskDto.Score::class, AiTaskDto.Categorize::class).none { unconfigured.isAvailable(it) }).isTrue()

        fakeScript("score.sh")
        val sender = LocalAiTaskSender(LocalProcessProperties(localScriptDir = dir.path))
        assertSoftly { softly ->
            softly.assertThat(sender.isAvailable(AiTaskDto.Score::class)).isTrue()
            softly.assertThat(sender.isAvailable(AiTaskDto.Embed::class)).isFalse()
            softly.assertThat(sender.isAvailable(AiTaskDto.Categorize::class)).isFalse()
        }
    }

    @Test
    fun `호출과 같은 이름의 스크립트를 Lambda 페이로드 키 인자로 띄우고 기다리지 않는다`() {
        // given: 받은 인자를 파일에 적는 가짜 스크립트 — 함수 이름은 AI repo 모듈과 같다(embedder·score·categorize)
        val embedder = fakeScript("embedder.sh")
        val categorize = fakeScript("categorize.sh")
        val sender = LocalAiTaskSender(LocalProcessProperties(localScriptDir = dir.path))

        // when
        sender.send(AiTaskDto.Embed(galleryId = 42, photoIds = listOf(1, 2, 3)))
        sender.send(AiTaskDto.Categorize(galleryId = 42, jobId = 9))

        // then: 비동기라 잠깐 기다려 본다
        waitFor { embedder.exists() && categorize.exists() }
        assertThat(embedder.readText().trim()).isEqualTo("--gallery-id 42 --photo-ids 1,2,3")
        assertThat(categorize.readText().trim()).isEqualTo("--gallery-id 42 --job-id 9")
    }

    @Test
    fun `exact photo 는 embedder 스크립트가 있어도 지원하지 않는다`() {
        fakeScript("embedder.sh")
        val sender = LocalAiTaskSender(LocalProcessProperties(localScriptDir = dir.path))
        val task = AiTaskDto.ExactPhoto(jobId = 1, attemptCount = 1, jobType = "EMBEDDING", photoId = 2, galleryId = 3, storageKey = "k", revisionId = 4)

        assertThat(sender.isAvailable(AiTaskDto.Embed::class)).isTrue()
        assertThat(sender.isAvailable(AiTaskDto.ExactPhoto::class)).isFalse()
        assertThatThrownBy { sender.send(task) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.AI_TASK_SEND_FAILED)
    }

    @Test
    fun `프로세스를 못 띄우면 호출 실패 코드다`() {
        val sender = LocalAiTaskSender(LocalProcessProperties(localScriptDir = dir.path))
        assertThatThrownBy { sender.send(AiTaskDto.Categorize(galleryId = 1, jobId = 1)) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.AI_TASK_SEND_FAILED)
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
