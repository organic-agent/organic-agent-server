package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.OpsAlertDto
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.OpsAlertSender
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 잡이 DONE 으로 닫힐 때 그 잡의 몫인 분석 실패 사진을 세고, 한 장이라도 있으면 운영 채널에 알린다. 알림에는 백오피스의 그 갤러리 링크가 있어
 * 관리자가 바로 실패한 사진만 다시 분석할 수 있다(백오피스가 없는 dev 는 링크를 뺀다). 실패 사진은 미분류로 남을 뿐 화면에서 따로 드러나지 않아, 알리지 않으면 아무도 모른다.
 *
 * "그 잡의 몫"은 같은 갤러리의 직전 DONE 잡이 끝난 뒤부터 이 잡이 끝날 때까지 실패로 표시된 사진이다. 잡 생성 시각으로 자르지 않는다 —
 * 임베딩은 잡과 무관하게 새 사진을 따라가서 잡이 생기기 전에 실패한 사진도 있다. 기준을 DONE 잡으로 두는 것은 알림이 DONE 에서만
 * 나가기 때문이다 — FAILED 잡을 기준으로 삼으면 그 잡 동안의 실패를 아무도 알리지 않는다. 끝을 이 잡의 종료 시각으로 자르면
 * 닫힌 뒤 집계 전에 생긴 실패를 이번과 다음 알림이 두 번 세지 않는다.
 *
 * 알림은 운영 확인용이라 실패가 잡 닫기를 되돌리면 안 된다. 그래서 [AnalysisJobEventRecorder]처럼 호출자의 트랜잭션이 커밋된 뒤에 세고 보내며,
 * 어떤 실패도 삼키고 로그만 남긴다. 알림에는 갤러리 제목 같은 고객 정보를 싣지 않는다 — 운영 채널은 외부 서비스다.
 */
@Component
class FailedPhotoAlerter(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val opsAlertSender: OpsAlertSender,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    companion object {
        /** 직전에 끝난 잡이 없을 때의 기준 시각 — 갤러리의 실패를 전부 센다. */
        private val BEGINNING: ZonedDateTime = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** @param finishedAt 이 잡을 DONE 으로 닫은 시각 — 닫은 쪽이 쓴 값 그대로다. 커밋 뒤에 잡을 다시 읽으면 영속성 컨텍스트의 옛 값이 나올 수 있다. */
    fun alertIfAny(job: AnalysisJob, finishedAt: ZonedDateTime) {
        val jobId = job.requiredId
        val galleryId = job.galleryId
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = check(jobId, galleryId, finishedAt)
                },
            )
        } else {
            check(jobId, galleryId, finishedAt)
        }
    }

    private fun check(jobId: Long, galleryId: Long, until: ZonedDateTime) {
        try {
            val since = analysisJobRepository.findFirstByGalleryIdAndIdLessThanAndStatusOrderByIdDesc(galleryId, jobId, AnalysisStatus.DONE)
                ?.finishedAt ?: BEGINNING
            val failedByError = photoPipelineRepository.countAnalysisFailuresByError(galleryId, since, until)
            val failed = failedByError.values.sum()
            if (failed == 0L) return

            log.warn("event=analysis.failed_photos job={} gallery={} failed={} byError={}", jobId, galleryId, failed, failedByError)
            if (!opsAlertSender.isAvailable()) {
                log.info("운영 알림 채널이 설정되지 않아 갤러리 {} 의 실패 사진 알림을 로그로만 남긴다", galleryId)
                return
            }
            opsAlertSender.send(alertOf(jobId, galleryId, failed, failedByError))
        } catch (e: RuntimeException) {
            log.error("갤러리 {} 잡 {} 의 실패 사진 알림 실패 — 파이프라인은 계속한다", galleryId, jobId, e)
        }
    }

    private fun alertOf(jobId: Long, galleryId: Long, failed: Long, failedByError: Map<String, Long>): OpsAlertDto {
        val progress = photoPipelineRepository.progressOf(galleryId, liveSince = ZonedDateTime.now(clock))
        val reasons = failedByError.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { (error, photos) -> "$error ${photos}장" }

        // 재처리는 백오피스에서 하므로, 백오피스가 없는 환경에서는 링크와 재처리 안내를 함께 뺀다.
        val backoffice = properties.backofficeGalleryUrl(galleryId)
            ?.let { listOf("백오피스에서 열기: $it", "다시 분석: 재처리 범위 `FAILED_ONLY`(실패한 사진만)") }
            .orEmpty()

        return OpsAlertDto(
            title = "분석 실패 사진 ${failed}장 · 갤러리 $galleryId",
            lines = listOf(
                "잡 ${jobId}이 끝났고, 이번 분석에서 실패한 사진이 있어요. 실패한 사진은 미분류에 남아요.",
                "갤러리 전체: 업로드 ${progress.uploaded}장 중 실패 ${progress.failed}장",
                "사유: $reasons",
            ) + backoffice,
        )
    }
}
