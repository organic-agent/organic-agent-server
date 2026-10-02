package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.domain.AnalysisTrigger
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.CapturedLogs
import com.soma.wes.support.FakeAiTaskSender
import com.soma.wes.support.IntegrationTest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 서버가 분석 잡을 만드는 규칙 — 언제 만들고, 언제 만들지 않고, 실패한 잡을 몇 번까지 다시 돌리는가.
 * 시계를 고정하는 대신 사진의 `uploaded_at` 과 잡의 `created_at`·`finished_at` 을 과거로 옮긴다.
 */
@IntegrationTest
class AutoAnalysisStepTest @Autowired constructor(
    private val step: AutoAnalysisStep,
    private val analysisService: AnalysisService,
    private val analysisJobRepository: AnalysisJobRepository,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val aiTaskSender: FakeAiTaskSender,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        aiTaskSender.reset()
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("잡을 스스로 만들 때")
    inner class Create {

        @Test
        fun `업로드가 조용해지면 요청이 없어도 AUTO 잡을 만든다`() {
            // given — 브라우저가 분석을 요청하기 전에 닫혔다
            uploadedMinutesAgo(count = 3, minutes = 2)

            // when
            val created = CapturedLogs(AnalysisJobCreator::class).use { logs ->
                step.advance()
                logs.eventsOf("job.created").single().formattedMessage
            }

            // then
            val job = jobs().single()
            assertSoftly { softly ->
                softly.assertThat(job.status).isEqualTo(AnalysisStatus.ANALYZING)
                softly.assertThat(job.trigger).isEqualTo(AnalysisTrigger.AUTO)
                softly.assertThat(created).isEqualTo(
                    "event=job.created job=${job.requiredId} gallery=${fixture.galleryId} trigger=AUTO retry=0 expected=3 conceptCount=null",
                )
            }
        }

        @Test
        fun `방금 올라온 사진이 있으면 아직 만들지 않는다`() {
            // given — 나눠 올리는 중간일 수 있다
            photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            // when
            step.advance()

            // then
            assertThat(jobs()).isEmpty()
        }

        @Test
        fun `올라오는 중인 사진이 있으면 만들지 않는다`() {
            // given
            uploadedMinutesAgo(count = 3, minutes = 2)
            photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when
            step.advance()

            // then
            assertThat(jobs()).isEmpty()
        }

        @Test
        fun `살아 있는 잡이 있으면 하나 더 만들지 않는다`() {
            // given
            uploadedMinutesAgo(count = 3, minutes = 2)
            val requested = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId

            // when
            step.advance()

            // then
            assertThat(jobs().map { it.requiredId }).containsExactly(requested)
        }

        @Test
        fun `오래전에 올라온 갤러리는 건드리지 않는다`() {
            // given — 분석한 적 없는 옛 갤러리. 배포하는 순간 이런 갤러리 전부에 잡이 생기면 안 된다
            uploadedMinutesAgo(count = 3, minutes = 7 * 60)

            // when
            step.advance()

            // then
            assertThat(jobs()).isEmpty()
        }

        @Test
        fun `끝난 잡 뒤에 사진이 더 올라오면 직전 잡의 컨셉 수를 이어 받아 다시 만든다`() {
            // given — 컨셉 수 4로 요청해 끝난 잡
            uploadedMinutesAgo(count = 2, minutes = 30)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.requiredId, conceptCount = 4).jobId
            close(first, AnalysisStatus.DONE, minutesAgo = 20)

            // when — 그 뒤에 사진이 더 올라왔다
            uploadedMinutesAgo(count = 1, minutes = 2)
            step.advance()

            // then
            val second = jobs().last()
            assertSoftly { softly ->
                softly.assertThat(jobs()).hasSize(2)
                softly.assertThat(second.trigger).isEqualTo(AnalysisTrigger.AUTO)
                softly.assertThat(second.conceptCount).isEqualTo(4)
            }
        }

        @Test
        fun `끝난 잡 뒤에 새 사진이 없으면 분류 안 된 사진이 남아 있어도 다시 만들지 않는다`() {
            // given — 잡은 끝났는데 분류되지 않은 사진이 남았다. 여기서 다시 만들면 같은 잡이 끝없이 되풀이된다
            uploadedMinutesAgo(count = 2, minutes = 30)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId
            close(first, AnalysisStatus.DONE, minutesAgo = 20)

            // when
            step.advance()

            // then
            assertThat(jobs()).hasSize(1)
        }

        @Test
        fun `두 스윕이 동시에 돌아도 잡은 하나이고 예외가 새지 않는다`() {
            // given
            uploadedMinutesAgo(count = 3, minutes = 2)
            val barrier = CyclicBarrier(2)

            // when
            val results = Executors.newFixedThreadPool(2).use { executor ->
                val futures = (1..2).map { executor.submit<Result<Unit>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching { step.advance() }
                } }
                futures.map { it.get(10, TimeUnit.SECONDS) }
            }

            // then
            assertSoftly { softly ->
                softly.assertThat(results).allSatisfy { assertThat(it.isSuccess).isTrue() }
                softly.assertThat(jobs()).hasSize(1)
            }
        }

        @Test
        fun `자동 생성과 분석 요청이 동시에 와도 잡은 하나이고 요청은 그 잡을 받는다`() {
            // given
            uploadedMinutesAgo(count = 3, minutes = 2)
            val barrier = CyclicBarrier(2)

            // when
            val requested = Executors.newFixedThreadPool(2).use { executor ->
                val auto = executor.submit<Result<Unit>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching { step.advance() }
                }
                val request = executor.submit<Result<Long>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching { analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId }
                }
                assertThat(auto.get(10, TimeUnit.SECONDS).isSuccess).isTrue()
                request.get(10, TimeUnit.SECONDS)
            }

            // then — 요청은 409 가 아니라 살아 있는 그 잡을 받는다
            assertSoftly { softly ->
                softly.assertThat(jobs()).hasSize(1)
                softly.assertThat(requested.getOrNull()).isEqualTo(jobs().single().requiredId)
            }
        }
    }

    @Nested
    @DisplayName("실패한 잡을 다시 돌릴 때")
    inner class Retry {

        @Test
        fun `일시적 실패는 2분 뒤와 10분 뒤 두 번까지 RETRY 잡을 만들고 세 번째는 만들지 않는다`() {
            // given — 일시적 실패로 닫힌 잡. 사진은 모든 잡보다 먼저 올라왔다(새 사진 때문에 AUTO 잡이 생기는 경우와 가른다)
            uploadedMinutesAgo(count = 2, minutes = 120)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.requiredId, conceptCount = 3).jobId
            close(first, AnalysisStatus.FAILED, minutesAgo = 1, errorCode = "CATEGORIZE_TIMEOUT")

            // when — 2분이 지나기 전에는 기다린다
            step.advance()
            assertThat(jobs()).hasSize(1)

            // 2분 뒤 첫 재시도
            close(first, AnalysisStatus.FAILED, minutesAgo = 3, errorCode = "CATEGORIZE_TIMEOUT")
            step.advance()
            val retry1 = jobs().last()

            // 첫 재시도도 실패 — 10분이 지나기 전에는 기다리고, 지나면 두 번째 재시도
            close(retry1.requiredId, AnalysisStatus.FAILED, minutesAgo = 5, errorCode = "SCORE_STAGE_DOWN")
            step.advance()
            assertThat(jobs()).hasSize(2)
            close(retry1.requiredId, AnalysisStatus.FAILED, minutesAgo = 11, errorCode = "SCORE_STAGE_DOWN")
            step.advance()
            val retry2 = jobs().last()

            // 두 번째 재시도도 실패 — 더는 만들지 않는다
            close(retry2.requiredId, AnalysisStatus.FAILED, minutesAgo = 60, errorCode = "CATEGORIZE_FAILED")
            step.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(jobs()).hasSize(3)
                softly.assertThat(listOf(retry1, retry2).map { it.trigger }).containsOnly(AnalysisTrigger.RETRY)
                softly.assertThat(listOf(retry1, retry2).map { it.retryCount }).containsExactly(1, 2)
                softly.assertThat(retry2.conceptCount).isEqualTo(3)
            }
        }

        @Test
        fun `다시 돌려도 같은 결과일 실패는 다시 돌리지 않는다`() {
            // given
            uploadedMinutesAgo(count = 2, minutes = 60)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId
            close(first, AnalysisStatus.FAILED, minutesAgo = 30, errorCode = "FOLDER_FAILED")

            // when
            step.advance()

            // then
            assertThat(jobs()).hasSize(1)
        }

        @Test
        fun `실패한 뒤 사용자가 다시 요청했으면 그 실패는 다시 돌리지 않고 재시도 횟수는 처음부터 센다`() {
            // given — 실패 뒤 사용자가 버튼을 눌러 새 잡이 돌고 있다
            uploadedMinutesAgo(count = 2, minutes = 60)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId
            close(first, AnalysisStatus.FAILED, minutesAgo = 30, errorCode = "CATEGORIZE_TIMEOUT")
            val second = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId

            // when
            step.advance()

            // then
            assertSoftly { softly ->
                softly.assertThat(jobs().map { it.requiredId }).containsExactly(first, second)
                softly.assertThat(jobs().last().retryCount).isZero()
            }
        }

        @Test
        fun `실패한 뒤 입력이 그대로면 AUTO 잡은 만들지 않는다`() {
            // given — 재시도 대상이 아닌 실패, 그리고 그 뒤 새 사진이 없다
            uploadedMinutesAgo(count = 2, minutes = 30)
            val first = analysisService.request(fixture.galleryId, fixture.photographer.requiredId).jobId
            close(first, AnalysisStatus.FAILED, minutesAgo = 20, errorCode = "NOTHING_TO_ANALYZE")

            // when
            step.advance()

            // then
            assertThat(jobs()).hasSize(1)
        }
    }

    private fun jobs(): List<AnalysisJob> = analysisJobRepository.findAll().sortedBy { it.requiredId }

    private fun uploadedMinutesAgo(count: Int, minutes: Int): List<Long> {
        val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count)
        jdbcTemplate.update(
            "UPDATE photos SET uploaded_at = now() - make_interval(mins => ?) WHERE id IN (${photoIds.joinToString(",")})",
            minutes,
        )
        return photoIds
    }

    /** 잡을 [minutesAgo]분 전에 닫힌 것으로 만든다. 만든 시각은 그보다 조금 앞에 둔다. */
    private fun close(jobId: Long, status: AnalysisStatus, minutesAgo: Int, errorCode: String? = null) {
        jdbcTemplate.update(
            """
            UPDATE analysis_jobs
            SET status = ?, error_code = ?, error = ?,
                finished_at = now() - make_interval(mins => ?),
                created_at = now() - make_interval(mins => ?)
            WHERE id = ?
            """.trimIndent(),
            status.name, errorCode, errorCode, minutesAgo, minutesAgo + 1, jobId,
        )
    }
}
