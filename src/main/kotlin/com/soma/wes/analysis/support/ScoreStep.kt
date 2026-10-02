package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.ScoreWorkerDto
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.ScoreWorkerPool
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 파이프라인 2단계: GPU score 워커 제어 + score Lambda 폴백. 잡과 무관하게 회차마다 돈다. 점수는 워커가 `photo_analysis`를 직접 집어 내므로
 * 여기서는 "일이 있으면 켜고, 일이 없는데 안 꺼졌으면 끄고, 워커가 못 내면 Lambda로 보낸다"만 한다.
 *
 * - 켜기: backlog(점수 없는 UPLOADED 사진) > 0 ∧ 켜진 워커 0. 벡터가 오기 전에 미리 켜 부팅이 임베딩과 겹치게 한다.
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

    /** 워커가 "내고 있다"고 마지막으로 믿은 시각 — 점수가 늘었거나 아직 부팅 유예 안이다. null이면 backlog가 없다. */
    @Volatile
    private var deliveringAt: ZonedDateTime? = null

    /** 켜기 호출 시각. Describe가 아직 stopped를 돌려주는 잠깐 동안 두 번 켜지 않게 한다. */
    @Volatile
    private var lastStartAt: ZonedDateTime? = null

    /** score 폴백을 갤러리마다 마지막으로 보낸 시각. */
    private val fallbackSentAt = ConcurrentHashMap<Long, ZonedDateTime>()

    /** score 폴백을 갤러리마다 보낸 횟수. 미점수 사진이 없어진 갤러리는 지운다 — 다음에 올라온 사진은 처음부터 센다. */
    private val fallbackCount = ConcurrentHashMap<Long, Int>()

    fun advance() {
        val now = ZonedDateTime.now(clock)
        val backlog = photoPipelineRepository.countScoreBacklog()
        observeProgress(backlog, now)

        if (isWorkerPoolActive) controlWorkers(backlog, now)
        if (backlog == 0L) {
            deliveringAt = null
            fallbackCount.clear()
            return
        }
        if (isFallbackDue(now)) fallback(now)
    }

    private val isWorkerPoolActive: Boolean
        get() = properties.gpu.enabled && scoreWorkerPool.isAvailable

    private fun observeProgress(backlog: Long, now: ZonedDateTime) {
        val previous = lastBacklog
        if (previous == null || backlog != previous) {
            lastProgressAt = now
            if (previous != null) deliveringAt = now
        }
        lastBacklog = backlog
    }

    /** 켜진 워커가 없으면 켜고, 일이 없는데 켜져 있으면 안전망으로 끈다. 호출 실패는 다음 스윕에 다시 본다. */
    private fun controlWorkers(backlog: Long, now: ZonedDateTime) {
        val workers = try {
            scoreWorkerPool.snapshot()
        } catch (e: AnalysisException) {
            log.warn("gpu snapshot failed code={}", e.errorCode.code)
            return
        }
        val up = workers.filter { it.isUp }

        try {
            if (backlog > 0 && up.isEmpty() && isStartDue(now)) {
                scoreWorkerPool.start()
                lastStartAt = now
                log.info("event=score.gpu.start backlog={} workers={}", backlog, workers.size)
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

    private fun isStartDue(now: ZonedDateTime): Boolean {
        val last = lastStartAt ?: return true
        return last.plus(properties.gpu.startGrace).isBefore(now)
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
        return true
    }
}
