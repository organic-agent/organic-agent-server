package com.soma.wes.activity.service

import com.soma.wes.activity.infrastructure.InMemoryActivityBuffer
import com.soma.wes.activity.repository.ActivityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.QueryTimeoutException
import java.time.ZonedDateTime

class ActivityFlushServiceTest {

    private val buffer = InMemoryActivityBuffer()
    private val repository = mock<ActivityRepository>()
    private val flushService = ActivityFlushService(buffer, repository)
    private val at = ZonedDateTime.parse("2026-10-13T12:00:00+09:00")

    @Test
    fun `쓰지 못한 시각은 메모장에 되돌려 다음 주기에 다시 쓴다`() {
        // given — 갤러리 2만 실패한다
        doThrow(QueryTimeoutException("시간 초과")).whenever(repository).recordGallery(eq(2L), any())
        buffer.markGallery(1, at)
        buffer.markGallery(2, at)
        buffer.markWorkspace(9, at)

        // when
        val written = flushService.flush()

        // then
        assertThat(written).isEqualTo(2)
        val left = buffer.drain()
        assertThat(left.galleries).containsExactlyEntriesOf(mapOf(2L to at))
        assertThat(left.workspaces).isEmpty()
    }

    @Test
    fun `모인 것이 없으면 DB를 부르지 않는다`() {
        assertThat(flushService.flush()).isZero()
    }
}
