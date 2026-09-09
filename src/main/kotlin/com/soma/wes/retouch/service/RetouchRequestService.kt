package com.soma.wes.retouch.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.photo.service.port.PhotoStorage
import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.domain.RetouchRoundStatus
import com.soma.wes.retouch.dto.request.RetouchRequestItem
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.retouch.support.RetouchPhotoLoader
import com.soma.wes.trash.service.ProductChildTrashService
import jakarta.persistence.EntityManager
import java.time.ZonedDateTime
import org.springframework.stereotype.Service

/** 호출자가 잡은 갤러리 잠금 안에서 선택 제출과 보정 요청을 한 트랜잭션으로 기록한다. */
@Service
class RetouchRequestService(
    private val roundRepository: RetouchRoundRepository,
    private val photoRepository: RetouchPhotoRepository,
    private val photoLoader: RetouchPhotoLoader,
    private val photoStorage: PhotoStorage,
    private val productChildTrashService: ProductChildTrashService,
    private val entityManager: EntityManager,
) {
    fun submit(
        gallery: Gallery,
        roundNo: Int,
        photoIds: List<Long>,
        requests: List<RetouchRequestItem>,
        at: ZonedDateTime,
    ): RetouchRound {
        val photos = photoLoader.loadPhotos(gallery.requiredId, photoIds)
        if (requests.size != requests.map { it.photoId }.toSet().size) {
            throw RetouchException(RetouchErrorCode.PHOTO_ALREADY_IN_ROUND)
        }
        if (requests.any { it.photoId !in photoIds }) {
            throw RetouchException(RetouchErrorCode.PHOTO_NOT_SELECTED)
        }
        val submitted = roundRepository.countByGalleryIdAndStatusNot(gallery.requiredId, RetouchRoundStatus.DRAFTING)
        if (gallery.maxRetouchRoundCount?.let { submitted >= it } == true) {
            throw RetouchException(RetouchErrorCode.MAX_RETOUCH_ROUND_COUNT_EXCEEDED)
        }
        val latest = roundRepository.findFirstByGalleryIdOrderByRoundNoDesc(gallery.requiredId)
        val expected = if (latest?.isDrafting == true) latest.roundNo else (latest?.roundNo ?: 0) + 1
        if (roundNo != expected || latest?.status == RetouchRoundStatus.REQUESTED) {
            throw RetouchException(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
        val existing = if (latest?.isDrafting == true) photoRepository.findAllByRoundId(latest.requiredId) else emptyList()
        requests.forEach { request ->
            if (request.annotationKey != null &&
                !request.annotationKey.startsWith("${photoStorage.galleryPrefix(gallery.requiredId)}retouch/annotations/")
            ) throw RetouchException(RetouchErrorCode.INVALID_ANNOTATION_KEY)
            RetouchPhoto(roundId = 0, galleryId = gallery.requiredId, photoId = request.photoId)
                .writeRequest(request.requestText, request.annotationKey, request.points)
        }

        val round = latest?.takeIf { it.isDrafting }
            ?: roundRepository.save(RetouchRound(galleryId = gallery.requiredId, roundNo = roundNo))
        val removed = existing.filter { it.photoId !in photoIds }
        removed.forEach { productChildTrashService.removeUserRetouchItem(round.requiredId, it.photoId) }
        if (removed.isNotEmpty()) entityManager.refresh(round)
        val existingByPhoto = existing.filter { it.photoId in photoIds }.associateBy { it.photoId }
        val requestsByPhoto = requests.associateBy { it.photoId }
        val items = photos.map { photo ->
            val item = existingByPhoto[photo.requiredId]
                ?: RetouchPhoto(roundId = round.requiredId, galleryId = gallery.requiredId, photoId = photo.requiredId)
            requestsByPhoto[photo.requiredId]?.let { item.writeRequest(it.requestText, it.annotationKey, it.points) }
            item
        }
        photoRepository.saveAll(items)
        round.submit(at)
        gallery.markRetouchStarted()
        return round
    }
}
