package com.soma.wes.retouch.service

import com.soma.wes.retouch.config.RetouchPdfProperties
import com.soma.wes.retouch.dto.RetouchPdfSnapshotDto
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.support.RetouchPdfRenderer
import com.soma.wes.retouch.support.RetouchPdfSnapshotLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RetouchPdfServiceUnitTest {
    @Test
    fun `동시 생성은 기다리지 않고 거절하며 끝난 뒤 다시 생성할 수 있다`() {
        // given
        val loader = mock<RetouchPdfSnapshotLoader>()
        val renderer = mock<RetouchPdfRenderer>()
        val snapshot = RetouchPdfSnapshotDto(galleryTitle = "샘플", photos = emptyList())
        whenever(loader.load(any(), any(), any(), any())).thenReturn(snapshot)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        whenever(renderer.render(snapshot)).thenAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "%PDF-fixture".toByteArray()
        }
        val service = RetouchPdfService(loader, renderer, RetouchPdfProperties())
        // when & then
        Executors.newSingleThreadExecutor().use { executor ->
            val first = executor.submit<ByteArray> { service.download(1L, 1, 1L, "all").bytes }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                assertThatThrownBy { service.download(1L, 1, 1L, "all") }
                    .isInstanceOf(RetouchException::class.java).extracting("errorCode")
                    .isEqualTo(RetouchErrorCode.PDF_GENERATION_BUSY)
            } finally {
                release.countDown()
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotEmpty()
            assertThat(service.download(1L, 1, 1L, "all").bytes).isNotEmpty()
        }
    }
}
