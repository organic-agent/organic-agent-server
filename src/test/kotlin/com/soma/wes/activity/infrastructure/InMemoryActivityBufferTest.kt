package com.soma.wes.activity.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class InMemoryActivityBufferTest {

    private val buffer = InMemoryActivityBuffer()
    private val early = ZonedDateTime.parse("2026-10-13T12:00:00+09:00")
    private val late = early.plusSeconds(3)

    @Test
    fun `같은 갤러리는 가장 늦은 시각 하나만 남는다`() {
        // when — 늦은 시각이 먼저 와도
        buffer.markGallery(1, late)
        buffer.markGallery(1, early)
        buffer.markWorkspace(9, early)
        buffer.markWorkspace(9, late)

        // then
        val marks = buffer.drain()
        assertSoftly {
            it.assertThat(marks.galleries).containsExactlyEntriesOf(mapOf(1L to late))
            it.assertThat(marks.workspaces).containsExactlyEntriesOf(mapOf(9L to late))
        }
    }

    @Test
    fun `꺼내면 비고 그 뒤에 남긴 시각은 다음 번에 나온다`() {
        // given
        buffer.markGallery(1, early)
        buffer.drain()

        // when
        buffer.markGallery(2, late)

        // then
        assertThat(buffer.drain().galleries).containsOnlyKeys(2L)
        assertThat(buffer.drain().isEmpty).isTrue()
    }
}
