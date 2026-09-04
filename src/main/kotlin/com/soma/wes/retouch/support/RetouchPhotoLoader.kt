package com.soma.wes.retouch.support

import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import org.springframework.stereotype.Component

/**
 * 보정 회차에 담을 사진 요청을 검증한다.
 * 잘못된 항목이 하나라도 섞이면 전체를 거절한다.
 */
@Component
class RetouchPhotoLoader(
    private val photoRepository: PhotoRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
    private val properties: StorageProperties,
) {

    /**
     * 담을 수 있는 사진인지 확인하고, 노출 순서대로 돌려준다.
     * selection과 같이 PENDING을 거른다 — 보정을 맡길 사진은 실물이 있어야 한다.
     */
    fun loadPhotos(galleryId: Long, photoIds: Collection<Long>): List<Photo> {
        validatePhotoIds(photoIds)

        val cleanPhotoIds = photoIds.toSet()
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, cleanPhotoIds)
        if (photos.size != cleanPhotoIds.size) {
            throw RetouchException(RetouchErrorCode.PHOTO_NOT_IN_GALLERY)
        }
        if (photos.any { it.status == PhotoStatus.PENDING }) {
            throw RetouchException(RetouchErrorCode.PHOTO_NOT_UPLOADED)
        }
        return photos.sortedWith(Photo.DISPLAY_ORDER)
    }

    private fun validatePhotoIds(photoIds: Collection<Long>) {
        if (photoIds.isEmpty()) {
            throw RetouchException(RetouchErrorCode.EMPTY_PHOTO_IDS)
        }
        if (photoIds.size > properties.maxBatchSize) {
            throw RetouchException(RetouchErrorCode.TOO_MANY_PHOTOS)
        }
    }

    /**
     * 회차 안 중복을 걸러낸다. 호출자는 갤러리 행을 잠근 뒤에 불러야 한다 — 잠그지 않으면
     * 두 요청이 모두 이 검사를 통과해 유니크 제약에 걸린 한쪽이 500으로 실패한다.
     */
    fun validateNoneInRound(roundId: Long, photoIds: Collection<Long>) {
        if (retouchPhotoRepository.findAllByRoundIdAndPhotoIdIn(roundId, photoIds).isNotEmpty()) {
            throw RetouchException(RetouchErrorCode.PHOTO_ALREADY_IN_ROUND)
        }
    }
}
