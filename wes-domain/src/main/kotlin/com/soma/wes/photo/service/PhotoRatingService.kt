package com.soma.wes.photo.service

import com.soma.wes.activity.support.ActivityRecorder
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.response.PhotoRatingResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사진에 매기는 1~5점의 선호 별점.
 */
@Service
class PhotoRatingService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val photoRatingRepository: PhotoRatingRepository,
    private val activityRecorder: ActivityRecorder,
) {

    /** 부부만 공유 별점을 읽는다. 전체 목록으로 교체하면 삭제된 별점도 화면에서 지워진다. */
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<PhotoRatingResponse> {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        return photoRatingRepository.findAllByGalleryId(galleryId).map { PhotoRatingResponse.from(it) }
    }

    /** 별점을 매긴다. 같은 사진에 다시 부르면 덮어쓴다.*/
    @Transactional
    fun rate(
        galleryId: Long,
        photoId: Long,
        userId: Long,
        request: RatePhotoRequest,
    ): PhotoRatingResponse {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        val photo = photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        val rating = photoRatingRepository.findByPhotoId(photo.requiredId)
            ?.apply { rate(request.score, userId) }
            ?: photoRatingRepository.save(PhotoRating.of(photo.requiredId, request.score, userId))

        photoRatingRepository.flush()
        activityRecorder.recordGallery(galleryId)
        return PhotoRatingResponse.from(rating)
    }

    /**
     * 별점을 지운다. 매긴 적 없는 사진에도 성공한다.
     */
    @Transactional
    fun clear(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        val photo = photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        photoRatingRepository.deleteByPhotoId(photo.requiredId)
        activityRecorder.recordGallery(galleryId)
    }
}
