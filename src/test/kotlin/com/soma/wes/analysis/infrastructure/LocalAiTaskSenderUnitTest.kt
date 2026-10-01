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
        val sender = sender()
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
        val sender = sender()

        // when
        sender.send(AiTaskDto.Embed(galleryId = 42, photoIds = listOf(1, 2, 3)))
        sender.send(AiTaskDto.Categorize(galleryId = 42, jobId = 9))

        // then: 비동기라 잠깐 기다려 본다
        waitFor { embedder.exists() && categorize.exists() }
        assertThat(embedder.readText().trim()).isEqualTo("--gallery-id 42 --photo-ids 1,2,3")
        assertThat(categorize.readText().trim()).isEqualTo("--gallery-id 42 --job-id 9")
    }

    @Test
    fun `컨셉 수가 있으면 categorize 에 concept-count 인자로 넘긴다`() {
        val categorize = fakeScript("categorize.sh")
        val sender = sender()

        sender.send(AiTaskDto.Categorize(galleryId = 42, jobId = 9, conceptCount = 4))

        waitFor { categorize.exists() }
        assertThat(categorize.readText().trim()).isEqualTo("--gallery-id 42 --job-id 9 --concept-count 4")
    }

    @Test
    fun `앞 프로세스가 살아 있으면 같은 함수를 또 띄우지 않는다 - 임베더는 전송 실패, score 는 조용히 넘긴다`() {
        // given: 인자를 한 줄씩 덧붙이고 잠깐 살아 있는 가짜 스크립트
        val embedder = slowScript("embedder.sh")
        val score = slowScript("score.sh")
        val sender = sender()
        sender.send(AiTaskDto.Embed(galleryId = 1, photoIds = listOf(1)))
        sender.send(AiTaskDto.Score(galleryId = 1, photoIds = listOf(1)))
        waitFor { embedder.exists() && score.exists() }

        // when & then: 임베더는 배정을 되돌리도록 실패로 알린다
        assertThatThrownBy { sender.send(AiTaskDto.Embed(galleryId = 1, photoIds = listOf(2))) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.AI_TASK_SEND_FAILED)
        sender.send(AiTaskDto.Score(galleryId = 1, photoIds = listOf(2)))

        Thread.sleep(300)
        assertSoftly { softly ->
            softly.assertThat(embedder.readLines()).containsExactly("--gallery-id 1 --photo-ids 1")
            softly.assertThat(score.readLines()).containsExactly("--gallery-id 1 --photo-ids 1")
        }
    }

    @Test
    fun `앞 프로세스가 끝났으면 다시 띄운다`() {
        val score = fakeScript("score.sh")
        val sender = sender()
        sender.send(AiTaskDto.Score(galleryId = 1, photoIds = listOf(1)))
        waitFor { score.exists() && score.readText().trim() == "--gallery-id 1 --photo-ids 1" }
        Thread.sleep(300)

        sender.send(AiTaskDto.Score(galleryId = 1, photoIds = listOf(2)))

        waitFor { score.readText().trim() == "--gallery-id 1 --photo-ids 2" }
        assertThat(score.readText().trim()).isEqualTo("--gallery-id 1 --photo-ids 2")
    }

    @Test
    fun `exact photo 는 embedder 스크립트가 있어도 지원하지 않는다`() {
        fakeScript("embedder.sh")
        val sender = sender()
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
        val sender = sender()
        assertThatThrownBy { sender.send(AiTaskDto.Categorize(galleryId = 1, jobId = 1)) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.AI_TASK_SEND_FAILED)
    }

    /** pid 파일을 테스트 디렉토리에 두는 실행기 — 테스트끼리 앞 프로세스를 보지 않게 한다. */
    private fun sender(): LocalAiTaskSender =
        LocalAiTaskSender(LocalProcessProperties(localScriptDir = dir.path)).apply { pidDir = dir }

    /** 받은 인자를 한 줄씩 덧붙이고 2초 동안 살아 있는 스크립트. `exec`로 pid를 그대로 넘긴다(실제 스크립트가 python을 exec 하듯). */
    private fun slowScript(name: String): File {
        val recorded = File(dir, "$name.args")
        File(dir, name).apply {
            writeText("#!/bin/sh\necho \"$@\" >> '${recorded.path}'\nexec sleep 2\n")
            setExecutable(true)
        }
        return recorded
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
