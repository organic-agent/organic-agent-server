package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.StageCallDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.StageInvoker
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 임베더 배정 — 잡과 무관하게 스윕마다 돈다. 업로드가 끝났고 벡터·실패·배정이 없는 사진을 갤러리마다 50장씩 집어
 * `{galleryId, photoIds}` EVENT로 보낸다. 프론트가 죽어도 올라온 사진은 여기서 끝까지 임베딩된다.
 *
 * 걸음 하나: 오래된 배정 되돌리기 → 시도 상한 표시 → 빈 자리(전역 in-flight 상한)만큼 갤러리를 돌며 한 배치씩.
 * 집기와 배정 표시는 문장 하나(`SKIP LOCKED`)라 스윕 둘이 같은 갤러리를 봐도 겹치지 않는다. Lambda 호출은
 * 트랜잭션 밖이고, 호출 자체가 실패하면 배정을 되돌려 다음 스윕이 다시 집는다.
 */
@Service
class EmbedDispatcher(
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val stageInvoker: StageInvoker,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 보낸 배치 수를 돌려준다. 실행기가 없으면(로컬·테스트) 아무것도 하지 않는다. */
    fun dispatch(): Int {
        if (!stageInvoker.isAvailable(StageCallDto.Embed::class)) return 0
        val now = ZonedDateTime.now(clock)

        val released = photoPipelineRepository.releaseStaleDispatches(before = now.minus(properties.embedRedispatchAfter))
        val exceeded = photoPipelineRepository.markEmbedAttemptsExceeded(properties.embedMaxAttempts, now)
        if (released > 0 || exceeded > 0) {
            log.warn("embed dispatch released={} exceeded={}", released, exceeded)
        }

        var slots = properties.embedMaxInFlight - photoPipelineRepository.countInFlightEmbedBatches()
        if (slots <= 0) return 0
        val galleryIds = photoPipelineRepository.findGalleryIdsWithEmbedBacklog(properties.embedMaxAttempts)
        if (galleryIds.isEmpty()) return 0

        // 갤러리를 돌며 한 배치씩 — 큰 갤러리 하나가 자리를 독식하지 않고, 자리가 남으면 다시 한 바퀴.
        var sent = 0
        while (slots > 0) {
            var sentThisRound = 0
            for (galleryId in galleryIds) {
                if (slots == 0) break
                val photoIds = photoPipelineRepository.claimForEmbedding(
                    galleryId = galleryId,
                    limit = properties.embedBatchSize,
                    maxAttempts = properties.embedMaxAttempts,
                )
                if (photoIds.isEmpty()) continue
                if (!invoke(StageCallDto.Embed(galleryId = galleryId, photoIds = photoIds))) return sent
                slots--
                sent++
                sentThisRound++
            }
            if (sentThisRound == 0) break
        }
        return sent
    }

    /** 호출 실패는 배정을 되돌리고 걸음을 멈춘다 — 권한·스로틀링이면 남은 갤러리도 같이 실패할 것이라 다음 스윕에 맡긴다. */
    private fun invoke(call: StageCallDto.Embed): Boolean = try {
        stageInvoker.invoke(call)
        log.info("embed dispatch gallery={} photos={}", call.galleryId, call.photoIds.size)
        true
    } catch (e: AnalysisException) {
        photoPipelineRepository.releaseClaim(call.photoIds)
        log.warn("embed dispatch failed — 배정을 되돌리고 다음 스윕에 다시 보낸다: gallery={} photos={} code={}", call.galleryId, call.photoIds.size, e.errorCode.code)
        false
    }
}
