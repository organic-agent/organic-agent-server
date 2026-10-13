package com.soma.wes.global.logging

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/** 로그 키를 싣고 비우는 규칙 — 스케줄러 스레드는 재사용되므로, 비우지 못하면 앞 갤러리의 값이 다음 줄에 남는다. */
class LogContextUnitTest {

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Nested
    @DisplayName("스케줄러 회차를 감쌀 때")
    inner class Sweep {

        @Test
        fun `회차마다 새 traceId 를 달고 끝나면 전부 비운다`() {
            // when
            val first = LogContext.sweep { MDC.get(LogContext.TRACE_ID) }
            val second = LogContext.sweep { MDC.get(LogContext.TRACE_ID) }

            // then
            assertSoftly { softly ->
                softly.assertThat(first).startsWith(LogContext.SWEEP_TRACE_PREFIX)
                softly.assertThat(first).hasSize(LogContext.SWEEP_TRACE_PREFIX.length + LogContext.SWEEP_TRACE_LENGTH)
                softly.assertThat(second).isNotEqualTo(first)
                softly.assertThat(MDC.getCopyOfContextMap().orEmpty()).isEmpty()
            }
        }

        @Test
        fun `회차가 예외로 끝나도 비운다`() {
            // when & then
            assertThatThrownBy {
                LogContext.sweep {
                    MDC.put(LogContext.GALLERY_ID, "7")
                    error("스윕 실패")
                }
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(MDC.getCopyOfContextMap().orEmpty()).isEmpty()
        }
    }

    @Nested
    @DisplayName("갤러리를 실을 때")
    inner class Gallery {

        @Test
        fun `블록 안에서만 갤러리와 잡이 보이고 끝나면 사라진다`() {
            // when
            val inside = LogContext.gallery(galleryId = 7, jobId = 3) {
                MDC.get(LogContext.GALLERY_ID) to MDC.get(LogContext.JOB_ID)
            }

            // then
            assertSoftly { softly ->
                softly.assertThat(inside).isEqualTo("7" to "3")
                softly.assertThat(MDC.get(LogContext.GALLERY_ID)).isNull()
                softly.assertThat(MDC.get(LogContext.JOB_ID)).isNull()
            }
        }

        @Test
        fun `겹쳐 실으면 안쪽이 끝난 뒤 바깥 값으로 돌아오고 traceId 는 건드리지 않는다`() {
            // when
            val seen = LogContext.sweep {
                val traceId = MDC.get(LogContext.TRACE_ID)
                LogContext.gallery(galleryId = 7, jobId = 3) {
                    LogContext.gallery(galleryId = 8) { }
                    Triple(MDC.get(LogContext.GALLERY_ID), MDC.get(LogContext.JOB_ID), MDC.get(LogContext.TRACE_ID) == traceId)
                }
            }

            // then
            assertThat(seen).isEqualTo(Triple("7", "3", true))
        }
    }
}
