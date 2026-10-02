package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.photo.repository.PhotoRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 갤러리의 사진 장수 한도. 업로드·URL 재발급·휴지통 복원이 같은 갤러리 잠금 아래에서 센다.
 *
 * 세는 것은 살아 있는 사진 중 "올라왔거나 아직 올라올 수 있는" 것이다 — 휴지통 사진과, PUT URL 이 죽은 PENDING 은 빠진다
 * ([PhotoRepository.countAgainstQuota]). URL 이 죽은 행을 세면 업로드에 실패한 만큼 한도가 줄어, 다시 올리는 것이 막힌다.
 */
@Service
class GalleryPhotoQuota(
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val clock: Clock,
) {

    /** 잠그고 바로 센다. 늘어날 장수를 미리 아는 호출자(휴지통 복원)가 쓴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireCapacity(galleryId: Long, additionalPhotoCount: Int): Gallery {
        val gallery = galleryRepository.requireWithLockById(galleryId)
        checkCapacity(gallery, additionalPhotoCount)
        return gallery
    }

    /**
     * 갤러리를 잠그기만 한다. 늘어날 장수를 잠근 뒤에야 알 수 있는 호출자(업로드 발급 — 같은 지문의 사진이 이미 있는지 봐야
     * 새로 만들 장수가 나온다)가 먼저 부르고, 센 뒤 [requireCapacity]로 넘긴다. 잠금 없이 세면 동시 요청 둘이 같은 사진을 두 번 만든다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock(galleryId: Long): Gallery = galleryRepository.requireWithLockById(galleryId)

    /** [gallery]는 이 트랜잭션에서 [lock]으로 잠근 것이어야 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireCapacity(gallery: Gallery, additionalPhotoCount: Int) {
        checkCapacity(gallery, additionalPhotoCount)
    }

    private fun checkCapacity(gallery: Gallery, additionalPhotoCount: Int) {
        val limit = gallery.planMaxPhotoCount ?: return
        val counted = photoRepository.countAgainstQuota(gallery.requiredId, clock.instant())
        if (counted + additionalPhotoCount > limit) {
            throw GalleryException(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
        }
    }
}
