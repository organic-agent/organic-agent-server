package com.soma.wes.analysis.support

import com.soma.wes.analysis.dto.AiFailureDto
import com.soma.wes.analysis.dto.AiFailureMessageDto
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.service.AiFailureService
import com.soma.wes.analysis.service.port.AiFailureQueue
import com.soma.wes.global.config.AnalysisPoolRoute
import java.time.ZonedDateTime
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** 실패 큐 리스너가 언제 메시지를 지우는지 — 처리했거나 읽을 수 없으면 지우고, 처리 중 예외면 남겨 가시성 시간 뒤 다시 받는다. */
class AiFailureListenerUnitTest {

    private val service = mock<AiFailureService>()
    private val queue = RecordingQueue()
    private val listener = AiFailureListener(queue, service, AnalysisPoolRoute())

    private fun failure(task: AiTaskDto?) = AiFailureDto(
        task = task,
        functionName = "wes-dev-categorize",
        condition = "RetriesExhausted",
        errorType = "RuntimeError",
        errorMessage = "boom",
        failedAt = ZonedDateTime.now(),
    )

    @Test
    fun `처리한 메시지와 읽을 수 없는 메시지는 지우고 처리 중 예외가 난 메시지는 남긴다`() {
        // given
        val broken = failure(AiTaskDto.Categorize(galleryId = 1, jobId = 9))
        whenever(service.handle(any())).then { invocation ->
            if (invocation.getArgument<AiFailureDto>(0) == broken) throw IllegalStateException("db down")
        }
        queue.next = listOf(
            AiFailureMessageDto("ok", failure(AiTaskDto.Categorize(galleryId = 1, jobId = 7))),
            AiFailureMessageDto("unreadable", null),
            AiFailureMessageDto("broken", broken),
        )

        // when
        val handled = listener.pollOnce()

        // then
        assertSoftly { softly ->
            softly.assertThat(handled).isEqualTo(2)
            softly.assertThat(queue.deleted).containsExactly("ok", "unreadable")
        }
    }

    @Test
    fun `빈 응답이면 아무것도 하지 않는다`() {
        // given
        whenever(service.handle(any())) doThrow IllegalStateException("불리면 안 된다")

        // when
        val handled = listener.pollOnce()

        // then
        assertSoftly { softly ->
            softly.assertThat(handled).isZero()
            softly.assertThat(queue.deleted).isEmpty()
        }
    }

    private class RecordingQueue : AiFailureQueue {
        var next: List<AiFailureMessageDto> = emptyList()
        val deleted = mutableListOf<String>()

        override fun receive(): List<AiFailureMessageDto> = next.also { next = emptyList() }

        override fun delete(receiptHandle: String) {
            deleted += receiptHandle
        }
    }
}
