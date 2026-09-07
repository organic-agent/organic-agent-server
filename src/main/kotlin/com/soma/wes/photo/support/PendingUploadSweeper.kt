package com.soma.wes.photo.support

import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.dto.PendingPhotoDto
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.service.PhotoStorage
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 완료 통보 없이 남은 PENDING 사진을 서버가 스스로 마무리한다. 프론트가 죽어도 S3에 올라간 원본은 갤러리에 나타나야 하고,
 * 끝내 오지 않은 행은 버려져야 한다.
 *
 * 규칙은 셋([StorageProperties]): 발급 1분 뒤 첫 HeadObject, 없으면 10분마다 다시, 24시간이 지나도 없으면 휴지통.
 * 휴지통으로 보내기 직전에도 HeadObject 를 한 번 더 지나므로 "PUT 은 됐는데 통보만 못 한" 사진이 버려지지 않는다.
 * S3 조회는 트랜잭션 밖에서 하고 상태 갱신은 문장 하나씩이다 — 커넥션을 문 채 네트워크를 기다리지 않는다.
 */
@Component
class PendingUploadSweeper(
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val photoStorage: PhotoStorage,
    private val properties: StorageProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.storage.pending-sweep-interval:PT5S}")
    fun sweep() {
        val now = ZonedDateTime.now(clock)
        val candidates = photoPipelineRepository.findPendingToCheck(
            now = now,
            firstCheckAfter = properties.pendingFirstCheckAfter,
            recheckBefore = now.minus(properties.pendingRecheckEvery),
            limit = BATCH_SIZE,
        )
        if (candidates.isEmpty()) return

        val checked = candidates.mapNotNull { photo -> existsInStorage(photo)?.let { photo to it } }
        val present = checked.filter { (_, exists) -> exists }.map { (photo, _) -> photo }
        val absent = checked.filterNot { (_, exists) -> exists }.map { (photo, _) -> photo }
        val (expired, waiting) = absent.partition { it.createdAt.plus(properties.pendingGiveUpAfter).isBefore(now) }

        val uploaded = photoPipelineRepository.markUploaded(present.map { it.photoId }, now)
        val trashed = photoPipelineRepository.moveToTrash(expired.map { it.photoId }, now)
        photoPipelineRepository.touchPending(waiting.map { it.photoId }, now)

        if (uploaded > 0 || trashed > 0) {
            log.info("pending sweep checked={} uploaded={} trashed={} waiting={}", checked.size, uploaded, trashed, waiting.size)
        }
    }

    /** 저장소 조회 자체가 실패한 사진(null)은 이번 걸음에서 빼고 다음 걸음에 다시 본다 — 판단하지 않은 행을 옮기지 않는다. */
    private fun existsInStorage(photo: PendingPhotoDto): Boolean? = try {
        photoStorage.exists(photo.storageKey)
    } catch (e: PhotoException) {
        log.warn("pending sweep storage check failed photoId={} code={}", photo.photoId, e.errorCode.code)
        null
    }

    companion object {
        /** 한 걸음에서 확인하는 최대 장수. 500장 발급이 한 번에 만료돼도 스윕 두 걸음 안에 지난다. */
        private const val BATCH_SIZE = 300
    }
}
