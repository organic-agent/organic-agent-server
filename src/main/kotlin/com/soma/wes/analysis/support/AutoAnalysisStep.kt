package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.domain.AnalysisTrigger
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

/**
 * 파이프라인 0단계: 서버가 분석 잡을 만든다. 잡을 브라우저만 만들면, 업로드가 끝나고 분석을 요청하기 전에 창을 닫은 갤러리에는
 * 폴더가 생기지 않는다. 두 가지를 한다.
 *
 * **자동 생성** — 최근에 사진이 올라온 갤러리마다, 아래가 모두 참이면 [AnalysisTrigger.AUTO] 잡을 만든다.
 * 1. 살아 있는 잡이 없다.
 * 2. 마지막 사진이 올라온 지 [AnalysisProperties.autoAnalysisQuietAfter]가 지났고 올라오는 중인 사진이 없다.
 * 3. 잡을 만든 적이 없거나, 가장 최근 잡이 만들어진 뒤에 사진이 올라왔다.
 * 4. 분석 대상 중 아직 분류되지 않은 사진이 있다.
 *
 * 3번이 되풀이를 막는다. 실패한 잡의 입력이 그대로면 여기서는 다시 만들지 않고(그것은 아래 재시도의 일이다), 끝난 잡 뒤에
 * 분류 못 한 사진이 남아 있어도 새 사진이 없으면 같은 잡을 끝없이 다시 만들지 않는다.
 *
 * **자동 재시도** — 가장 최근 잡이 일시적 실패로 닫혔으면 [AnalysisProperties.autoRetryDelays]만큼 기다렸다가
 * [AnalysisTrigger.RETRY] 잡을 만든다. 대기 목록을 다 쓰면 멈추고 사용자의 요청을 기다린다.
 *
 * 갤러리마다 잡 생성은 자기 트랜잭션([AnalysisJobCreator])이다. 다른 스윕이나 분석 요청 API 가 먼저 만들었으면 유니크 충돌이 나는데,
 * "이미 누가 만들었다"는 뜻이라 삼키고 다음 갤러리로 간다.
 */
@Component
class AutoAnalysisStep(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val jobCreator: AnalysisJobCreator,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun advance() {
        if (!jobCreator.isRunnable) return
        val now = ZonedDateTime.now(clock)

        val recentUploads = photoPipelineRepository.findRecentUploads(uploadedSince = now.minus(properties.autoAnalysisWindow))
        for ((galleryId, lastUploadedAt) in recentUploads) {
            guarded(galleryId) { createIfDue(galleryId, lastUploadedAt, now) }
        }
        for (failed in analysisJobRepository.findLatestFailedSince(finishedSince = now.minus(properties.autoAnalysisWindow))) {
            guarded(failed.galleryId) { retryIfDue(failed, now) }
        }
    }

    /** 한 갤러리의 실패가 같은 회차의 다른 갤러리를 막지 않게 가둔다. 유니크 충돌은 실패가 아니라 "이미 있다"이다. */
    private fun guarded(galleryId: Long, block: () -> Unit) {
        LogContext.gallery(galleryId) {
            try {
                block()
            } catch (e: DataIntegrityViolationException) {
                log.debug("갤러리 {} 의 분석 잡은 다른 쪽이 먼저 만들었다", galleryId)
            } catch (e: RuntimeException) {
                log.error("갤러리 {} 자동 분석 잡 생성 실패 — 다음 회차에 다시 본다", galleryId, e)
            }
        }
    }

    private fun createIfDue(galleryId: Long, lastUploadedAt: ZonedDateTime, now: ZonedDateTime) {
        if (lastUploadedAt.plus(properties.autoAnalysisQuietAfter).isAfter(now)) return
        if (analysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, AnalysisStatus.ACTIVE)) return

        val latest = analysisJobRepository.findFirstByGalleryIdOrderByIdDesc(galleryId)
        if (latest != null && !lastUploadedAt.isAfter(latest.createdAt)) return
        val progress = analyzableProgress(galleryId, now) ?: return

        // 컨셉 수는 직전 잡의 값을 이어 쓴다 — 사용자가 한 번 답한 것을 자동 잡이 잊지 않게 한다.
        jobCreator.create(
            galleryId = galleryId,
            conceptCount = latest?.conceptCount,
            trigger = AnalysisTrigger.AUTO,
            retryCount = 0,
            expected = progress.expected,
        )
    }

    private fun retryIfDue(failed: AnalysisJob, now: ZonedDateTime) {
        val code = failed.errorCode ?: return
        if (!code.transient) return
        val delay = properties.autoRetryDelays.getOrNull(failed.retryCount) ?: return
        val finishedAt = failed.finishedAt ?: return
        if (finishedAt.plus(delay).isAfter(now)) return
        val progress = analyzableProgress(failed.galleryId, now) ?: return

        jobCreator.create(
            galleryId = failed.galleryId,
            conceptCount = failed.conceptCount,
            trigger = AnalysisTrigger.RETRY,
            retryCount = failed.retryCount + 1,
            expected = progress.expected,
        )
    }

    /** 지금 잡을 만들 만한 갤러리의 진행 — 올라오는 중인 사진이 없고, 분류되지 않은 대상이 남아 있다. 아니면 null. */
    private fun analyzableProgress(galleryId: Long, now: ZonedDateTime): GalleryAnalysisProgress? {
        val progress = photoPipelineRepository.progressOf(galleryId, liveSince = now.minus(properties.uploadQuietAfter))
        if (progress.livePending > 0) return null
        if (progress.expected == 0L || progress.isFullyCategorized) return null
        return progress
    }
}
