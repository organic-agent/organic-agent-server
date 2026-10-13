package com.soma.wes.activity.service

import com.soma.wes.activity.infrastructure.InMemoryActivityBuffer
import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 모아 쓰기(#286 (f))의 흐름 — 커밋한 활동만 메모장에 남고, 플러시가 그 시각을 쓴다.
 * 테스트 컨텍스트는 모아 쓰기를 끈 채라(기존 테스트는 바로 쓰기를 검증한다) 메모장을 켠 조합을 여기서 직접 만든다.
 */
@IntegrationTest
class ActivityBufferFlowTest @Autowired constructor(
    private val repository: ActivityRepository,
    private val galleryFixture: GalleryFixture,
    private val galleries: GalleryRepository,
    private val transactionTemplate: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) {

    private val clock = Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val buffer = InMemoryActivityBuffer()
    private val recorder = ActivityRecorder(repository, clock, buffer)
    private val flushService = ActivityFlushService(buffer, repository)

    @Test
    fun `커밋한 활동은 바로 쓰지 않고 플러시 때 갤러리와 작업공간 시각으로 쓴다`() {
        // given
        val galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
        val workspaceId = galleries.findById(galleryId).orElseThrow().workspaceId
        clear(galleryId, workspaceId)

        // when
        transactionTemplate.executeWithoutResult { recorder.recordGallery(galleryId) }
        val beforeFlush = repository.findGalleryActivity(listOf(galleryId))
        val written = flushService.flush()

        // then
        assertSoftly {
            it.assertThat(beforeFlush).isEmpty()
            it.assertThat(written).isEqualTo(1)
            it.assertThat(repository.findGalleryActivity(listOf(galleryId)).getValue(galleryId).toInstant()).isEqualTo(clock.instant())
            it.assertThat(repository.findWorkspaceActivity(listOf(workspaceId)).getValue(workspaceId).toInstant()).isEqualTo(clock.instant())
        }
    }

    @Test
    fun `롤백한 활동은 메모장에 남지 않는다`() {
        // given
        val galleryId = galleryFixture.멤버와_열린_갤러리().galleryId

        // when
        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                recorder.recordGallery(galleryId)
                error("업무 변경 실패")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        // then
        assertThat(buffer.drain().isEmpty).isTrue()
    }

    @Test
    fun `이미 더 늦은 시각이 있으면 늦게 쓴 플러시가 되돌리지 않는다`() {
        // given — 다른 서버가 더 늦은 시각을 먼저 썼다
        val galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
        val later = clock.instant().plusSeconds(60).atZone(clock.zone)
        repository.recordGallery(galleryId, later)

        // when
        transactionTemplate.executeWithoutResult { recorder.recordGallery(galleryId) }
        flushService.flush()

        // then
        assertThat(repository.findGalleryActivity(listOf(galleryId)).getValue(galleryId).toInstant()).isEqualTo(later.toInstant())
    }

    private fun clear(galleryId: Long, workspaceId: Long) {
        jdbc.update("DELETE FROM gallery_activity WHERE gallery_id = ?", galleryId)
        jdbc.update("DELETE FROM workspace_activity WHERE workspace_id = ?", workspaceId)
    }
}
