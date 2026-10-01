package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class GalleryPhotoQuota(
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
) {
    /** 업로드와 휴지통 복원은 같은 갤러리 잠금을 사용한다. 삭제된 사진은 한도에서 즉시 빠진다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireCapacity(galleryId: Long, additionalPhotoCount: Int): Gallery {
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val limit = gallery.planMaxPhotoCount
        if (limit != null && photoRepository.countByGalleryId(galleryId) + additionalPhotoCount > limit) {
            throw GalleryException(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
        }
        return gallery
    }
}
