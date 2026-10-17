package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.dto.ScoreWorkerDto
import com.soma.wes.analysis.dto.ScoreWorkerStateDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.analysis.service.port.ScoreWorkerPool
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 파이프라인 2단계: GPU score 워커 제어 + score Lambda 폴백. 잡과 무관하게 회차마다 돈다. 점수는 워커가 `photo_analysis`를 직접 집어 내므로
 * 여기서는 "일이 있으면 켜고, 일이 없는데 안 꺼졌으면 끄고, 워커가 못 내면 Lambda로 보낸다"만 한다.
 *
 * - 켜기: (backlog(점수 없는 UPLOADED 사진) > 0 ∨ [AnalysisProperties.Gpu.startOnUploadWithin] 안에 업로드가 시작됨) ∧ 켜진 워커 0.
 *   벡터가 오기 전에, 첫 사진이 올라오기 전에 미리 켜 부팅이 업로드·임베딩과 겹치게 한다.
 *   [AnalysisProperties.QualityStage.enabled]면 화질 점수(2단계) 대기도 워커의 일로 센다 — 워커는 1단계 대기가 비면 2단계를 집으므로,
 *   1단계만 세면 2단계 도중 안전망이 워커를 끈다. 폴백은 1단계만 본다(폴더가 기다리는 것은 1단계뿐이다).
 * - 늘리기: 켜진 워커가 있어도 1단계 대기가 [AnalysisProperties.Gpu.scaleOutBacklog] 이상이면 [AnalysisProperties.Gpu.maxWorkers]까지
 *   한 대 더 켠다. 동시 업로드가 많을 때만 대기가 그만큼 쌓인다. 워커는 `FOR UPDATE SKIP LOCKED`로 집어 둘이 같은 사진을 매기지 않는다(AI repo).
 * - 어느 것을 켜나: 켜진 워커가 없는 가용 영역의 것부터. 켜기가 실패하면(그 영역 GPU 재고 부족 등) 같은 걸음에서 다음 것을 켠다.
 * - 끄기: 워커의 유휴 30초 자기 정지(AI repo)가 1차다. 여기는 backlog 0 ∧ 켜진 지 [AnalysisProperties.Gpu.startGrace] 지남 ∧
 *   [AnalysisProperties.Gpu.idleStopAfter] 동안 점수 진행 없음일 때만 끄는 안전망이다.
 * - 폴백: backlog가 있는데 [AnalysisProperties.Gpu.fallbackAfter] 동안 워커가 뜨지 않거나 점수가 늘지 않으면, 벡터는 있는데 점수가
 *   없는 사진을 갤러리마다 [AnalysisProperties.scoreBatchSize]장씩 score Lambda에 보낸다(갤러리당 [AnalysisProperties.Gpu.fallbackInterval] 1회). GPU가 꺼져 있으면
 *   기다리지 않고 폴백만 돈다. score는 UPSERT라 워커와 겹쳐도 같은 값을 덮을 뿐이다. 한 갤러리에는
 *   [AnalysisProperties.scoreFallbackMax]번까지만 보낸다 — 그 뒤에도 점수가 없으면 잡의 진행 감시([CategorizeStep])가 처리한다.
 *
 * 진행·전송 시각은 인메모리다(컬럼 없음). 인스턴스가 여럿이면 각자 판단해 겹칠 수 있는데, 켜기·끄기·score 전부 멱등이라 무방하다.
 */
@Component
class ScoreStep(
    private val scoreWorkerPool: ScoreWorkerPool,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val aiTaskSender: AiTaskSender,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 마지막 스윕에서 본 대기 수(점수 없는 사진)와 그것이 마지막으로 바뀐 시각. 줄면 워커가 내고 있는 것이고, 늘면 새 사진이 들어온 것이다 —
     * 둘 다 "멈춤"이 아니다. 끝난 사진 전체를 세지 않으므로 사진이 쌓여도 이 판정의 비용은 대기 중인 양에만 비례한다.
     */
    @Volatile
    private var lastBacklog: Long? = null

    @Volatile
    private var lastProgressAt: ZonedDateTime? = null

    /** 마지막 스윕에서 본 1단계 대기 수. [lastBacklog]는 화질 점수(2단계) 대기까지 더한 워커의 일 전체다. */
    @Volatile
    private var lastScoreBacklog: Long? = null

    /** 워커가 "내고 있다"고 마지막으로 믿은 시각 — 점수가 늘었거나 아직 부팅 유예 안이다. null이면 backlog가 없다. */
    @Volatile
    private var deliveringAt: ZonedDateTime? = null

    /** 켜기 호출 시각. Describe가 아직 stopped를 돌려주는 잠깐 동안 두 번 켜지 않게 한다. */
    @Volatile
    private var lastStartAt: ZonedDateTime? = null

    /** 마지막 켜기가 점수 대기 없이 업로드 신호만으로였나 — [isStartDue]의 유예 길이가 달라진다. */
    @Volatile
    private var lastStartWasUploadOnly: Boolean = false

    /** score 폴백을 갤러리마다 마지막으로 보낸 시각. */
    private val fallbackSentAt = ConcurrentHashMap<Long, ZonedDateTime>()

    /** score 폴백을 갤러리마다 보낸 횟수. 미점수 사진이 없어진 갤러리는 지운다 — 다음에 올라온 사진은 처음부터 센다. */
    private val fallbackCount = ConcurrentHashMap<Long, Int>()

    companion object {
        /** 늘리기로 켠 뒤 다시 늘리기 전 기다림 — 켜기 직후 인스턴스가 PENDING 으로 보이기까지의 몇 초를 넉넉히 덮는다. */
        private val SCALE_OUT_GUARD: Duration = Duration.ofMinutes(1)
    }

    fun advance() {
        val now = ZonedDateTime.now(clock)
        val backlog = photoPipelineRepository.countScoreBacklog(dispatchedSince = now.minus(properties.embedRedispatchAfter))
        val workerBacklog = backlog + countQualityBacklog()
        observeProgress(workerBacklog, now)
        observeDelivery(backlog, now)

        if (isWorkerPoolActive) controlWorkers(workerBacklog = workerBacklog, scoreBacklog = backlog, now = now)
        if (backlog == 0L) {
            deliveringAt = null
            fallbackCount.clear()
            return
        }
        if (isFallbackDue(now)) fallback(now)
    }

    /**
     * score 폴백이 끝내 실패했다(실패 큐, #286 (c)) — 그 갤러리의 폴백 간격([AnalysisProperties.Gpu.fallbackInterval])이 [retryAt]에 차도록
     * 마지막 전송 시각을 앞당긴다. 다시 보내는 것은 다음 걸음의 [fallback]이고, 갤러리 상한([AnalysisProperties.scoreFallbackMax])은 그대로다.
     * 인메모리라 이 서버가 보낸 폴백에만 닿는다 — 다른 서버의 폴백이면 그 서버의 간격대로 간다. 지금 몇 번째였는지를 돌려준다(없으면 0).
     */
    fun expediteFallback(galleryId: Long, retryAt: (attempt: Int) -> ZonedDateTime): Int {
        val attempt = fallbackCount[galleryId] ?: return 0
        val sentAt = retryAt(attempt).minus(properties.gpu.fallbackInterval)
        fallbackSentAt.computeIfPresent(galleryId) { _, last -> if (sentAt.isBefore(last)) sentAt else last }
        return attempt
    }

    private fun countQualityBacklog(): Long =
        if (properties.qualityStage.enabled) photoPipelineRepository.countQualityBacklog() else 0L

    private val isWorkerPoolActive: Boolean
        get() = properties.gpu.enabled && scoreWorkerPool.isAvailable

    /** 워커의 일 전체(1·2단계)가 움직였나 — 안전망 끄기의 기준. */
    private fun observeProgress(workerBacklog: Long, now: ZonedDateTime) {
        val previous = lastBacklog
        if (previous == null || workerBacklog != previous) lastProgressAt = now
        lastBacklog = workerBacklog
    }

    /**
     * 1단계 대기가 움직였나 — 폴백의 기준. 워커 전체가 아니라 1단계만 본다: 1단계가 멈춘 채 2단계만 채워지면 폴더가 기다리는 사진이
     * 2단계가 끝날 때까지 폴백을 받지 못한다.
     */
    private fun observeDelivery(backlog: Long, now: ZonedDateTime) {
        val previous = lastScoreBacklog
        if (previous != null && backlog != previous) deliveringAt = now
        lastScoreBacklog = backlog
    }

    /** 켜진 워커가 없으면 켜고, 1단계 대기가 쌓이면 한 대 더 켜고, 일이 없는데 켜져 있으면 안전망으로 끈다. 호출 실패는 다음 스윕에 다시 본다. */
    private fun controlWorkers(workerBacklog: Long, scoreBacklog: Long, now: ZonedDateTime) {
        val backlog = workerBacklog
        val workers = try {
            scoreWorkerPool.snapshot()
        } catch (e: AnalysisException) {
            log.warn("gpu snapshot failed code={}", e.errorCode.code)
            return
        }
        val up = workers.filter { it.isUp }

        try {
            if (up.isEmpty() && isStartDue(now)) {
                val uploadOnly = backlog == 0L
                if (!uploadOnly || isUploadStarting(now)) {
                    startOne(workers, reason = if (uploadOnly) "upload" else "backlog", backlog = backlog)
                    lastStartAt = now
                    lastStartWasUploadOnly = uploadOnly
                }
            } else if (isScaleOutDue(up, scoreBacklog, now)) {
                startOne(workers, reason = "scale-out", backlog = scoreBacklog)
                lastStartAt = now
                lastStartWasUploadOnly = false
            }
            if (backlog == 0L) {
                up.filter { isPastGrace(it, now) && isIdle(it, now) }.forEach { worker ->
                    scoreWorkerPool.stop(worker.instanceId)
                    log.warn(
                        "event=score.gpu.stop instance={} reason=idle-safety-net launched={}",
                        worker.instanceId, worker.launchedAt,
                    )
                }
            }
        } catch (e: AnalysisException) {
            log.warn("gpu control failed code={} — 다음 스윕에서 다시 본다", e.errorCode.code)
        }
        if (up.any { !isPastGrace(it, now) }) deliveringAt = now
    }

    /**
     * 켜진 워커가 없는 가용 영역의 꺼진 인스턴스부터 하나를 켠다. 켜기가 실패하면 다음 것을 켠다 — 한 영역에 GPU 재고가 없어도 다른 영역
     * 예비가 받게(10-17 확인 전에는 늘 '꺼진 첫 대'만 다시 켜 재고 부족 동안 폴백까지 기다렸다). 전부 실패하면 다음 걸음에 다시 본다.
     */
    private fun startOne(workers: List<ScoreWorkerDto>, reason: String, backlog: Long) {
        val busyZones = workers.filter { it.isUp }.mapNotNull { it.zone }.toSet()
        val candidates = workers
            .filter { it.state == ScoreWorkerStateDto.STOPPED }
            .sortedBy { it.zone in busyZones }
        for (candidate in candidates) {
            try {
                scoreWorkerPool.start(candidate.instanceId)
                log.info(
                    "event=score.gpu.start instance={} zone={} backlog={} workers={} reason={}",
                    candidate.instanceId, candidate.zone, backlog, workers.size, reason,
                )
                return
            } catch (e: AnalysisException) {
                log.warn("event=score.gpu.start_failed instance={} zone={} code={} — 다음 워커로", candidate.instanceId, candidate.zone, e.errorCode.code)
            }
        }
        log.warn("event=score.gpu.no_start candidates={} reason={} — 다음 스윕에서 다시 본다", candidates.size, reason)
    }

    /** 늘리기는 켜기 직후 Describe 가 아직 STOPPED 를 돌려주는 동안 두 번 켜지 않게 [SCALE_OUT_GUARD]를 둔다. */
    private fun isScaleOutDue(up: List<ScoreWorkerDto>, scoreBacklog: Long, now: ZonedDateTime): Boolean {
        if (up.isEmpty() || up.size >= properties.gpu.maxWorkers) return false
        if (scoreBacklog < properties.gpu.scaleOutBacklog) return false
        val last = lastStartAt ?: return true
        return last.plus(SCALE_OUT_GUARD).isBefore(now)
    }

    private fun isUploadStarting(now: ZonedDateTime): Boolean =
        photoPipelineRepository.hasUploadStartedSince(now.minus(properties.gpu.startOnUploadWithin))

    /**
     * 켜기 호출 뒤 다시 켜지 않는 동안. 업로드 신호만으로 켰다면 짧게([AnalysisProperties.Gpu.startOnUploadWithin]) — 사진이 늦게 올라와
     * 워커가 일 없이 30초 만에 꺼졌을 때, 기동 유예(5분)를 다 기다리지 않고 점수 대기를 보고 다시 켜야 한다.
     */
    private fun isStartDue(now: ZonedDateTime): Boolean {
        val last = lastStartAt ?: return true
        val guard = if (lastStartWasUploadOnly) properties.gpu.startOnUploadWithin else properties.gpu.startGrace
        return last.plus(guard).isBefore(now)
    }

    private fun isPastGrace(worker: ScoreWorkerDto, now: ZonedDateTime): Boolean {
        val launchedAt = worker.launchedAt ?: return true
        return launchedAt.plus(properties.gpu.startGrace).isBefore(now)
    }

    private fun isIdle(worker: ScoreWorkerDto, now: ZonedDateTime): Boolean {
        val since = lastProgressAt ?: worker.launchedAt ?: return true
        return since.plus(properties.gpu.idleStopAfter).isBefore(now)
    }

    /** GPU가 없으면 바로, 있으면 워커가 [AnalysisProperties.Gpu.fallbackAfter] 동안 아무것도 내지 못했을 때. */
    private fun isFallbackDue(now: ZonedDateTime): Boolean {
        if (!isWorkerPoolActive) return true
        val since = deliveringAt ?: run {
            deliveringAt = now
            return false
        }
        return since.plus(properties.gpu.fallbackAfter).isBefore(now)
    }

    /** 갤러리마다 미점수 사진 전부를 [AnalysisProperties.scoreBatchSize]장씩. 호출 실패는 남은 갤러리도 같이 실패할 것이라 걸음을 멈춘다. */
    private fun fallback(now: ZonedDateTime) {
        if (!aiTaskSender.isAvailable(AiTaskDto.Score::class)) return
        val galleryIds = photoPipelineRepository.findGalleryIdsWithUnscoredPhotos()
        fallbackCount.keys.retainAll(galleryIds.toSet())
        for (galleryId in galleryIds) {
            if ((fallbackCount[galleryId] ?: 0) >= properties.scoreFallbackMax) continue
            val last = fallbackSentAt[galleryId]
            if (last != null && last.plus(properties.gpu.fallbackInterval).isAfter(now)) continue
            val delivered = LogContext.gallery(galleryId) { fallbackGallery(galleryId, now) }
            if (!delivered) return
        }
    }

    /** 갤러리 하나의 미점수 사진을 보낸다. 호출이 실패하면 false — 걸음을 멈추라는 뜻이다. */
    private fun fallbackGallery(galleryId: Long, now: ZonedDateTime): Boolean {
        val photoIds = photoPipelineRepository.findUnscoredPhotoIds(galleryId)
        if (photoIds.isEmpty()) return true
        val batches = photoIds.chunked(properties.scoreBatchSize)
        for (batch in batches) {
            try {
                aiTaskSender.send(AiTaskDto.Score(galleryId = galleryId, photoIds = batch))
            } catch (e: AnalysisException) {
                log.warn("score fallback failed gallery={} code={} — 다음 스윕에서 다시 보낸다", galleryId, e.errorCode.code)
                return false
            }
        }
        fallbackSentAt[galleryId] = now
        val attempt = fallbackCount.merge(galleryId, 1, Int::plus)
        log.info(
            "event=score.fallback gallery={} photos={} batches={} attempt={} max={}",
            galleryId, photoIds.size, batches.size, attempt, properties.scoreFallbackMax,
        )
        eventRecorder.recordForActiveJob(
            galleryId, AnalysisJobEventType.SCORE_FALLBACK,
            mapOf("photos" to photoIds.size, "batches" to batches.size, "attempt" to attempt, "max" to properties.scoreFallbackMax),
        )
        return true
    }
}
