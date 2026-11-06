package com.soma.wes.photo.service

import com.soma.wes.activity.support.ActivityRecorder
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.domain.PhotoRatingHistory
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.response.PhotoRatingHistoryResponse
import com.soma.wes.photo.dto.response.PhotoRatingResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRatingHistoryRepository
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 사진에 매기는 1~5점의 선호 별점과 그 변경 기록.
 */
@Service
class PhotoRatingService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val photoRatingRepository: PhotoRatingRepository,
    private val photoRatingHistoryRepository: PhotoRatingHistoryRepository,
    private val activityRecorder: ActivityRecorder,
    private val clock: Clock,
) {

    /** 부부만 공유 별점을 읽는다. 전체 목록으로 교체하면 삭제된 별점도 화면에서 지워진다. */
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<PhotoRatingResponse> {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        return photoRatingRepository.findAllByGalleryId(galleryId).map { PhotoRatingResponse.from(it) }
    }

    /**
     * 별점을 매긴다. 같은 사진에 다시 부르면 덮어쓴다.
     *
     * 점수나 매긴 사람이 바뀌었을 때만 기록 한 줄을 남긴다 — 같은 사람이 같은 점수를 다시 누르면 행이 그대로라
     * `rating_revision`도 오르지 않으므로, 기록도 남기지 않아야 둘이 맞물린다([PhotoRatingHistory]).
     */
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

        val existing = photoRatingRepository.findByPhotoId(photo.requiredId)
        val changed = existing == null || existing.score != request.score || existing.ratedBy != userId
        val rating = existing
            ?.apply { rate(request.score, userId) }
            ?: photoRatingRepository.save(PhotoRating.of(photo.requiredId, request.score, userId))

        if (changed) {
            recordHistory(photoId = photo.requiredId, score = rating.score, ratedBy = userId)
        }
        photoRatingRepository.flush()
        activityRecorder.recordGallery(galleryId)
        return PhotoRatingResponse.from(rating)
    }

    /**
     * 별점을 지운다. 매긴 적 없는 사진에도 성공한다 — 그때는 지운 행이 없어 기록도 남기지 않는다.
     */
    @Transactional
    fun clear(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        val photo = photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        val deleted = photoRatingRepository.deleteByPhotoId(photo.requiredId)
        if (deleted > 0) {
            recordHistory(photoId = photo.requiredId, score = null, ratedBy = userId)
        }
        activityRecorder.recordGallery(galleryId)
    }

    private fun recordHistory(photoId: Long, score: Int?, ratedBy: Long) {
        photoRatingHistoryRepository.save(
            PhotoRatingHistory(photoId = photoId, score = score, ratedBy = ratedBy, createdAt = ZonedDateTime.now(clock)),
        )
    }

    /**
     * 사진 한 장의 별점 기록, 최신순 최대 50건. 공유 별점 목록([list])과 같이 부부만 읽는다.
     * 휴지통 사진은 별점 매기기·목록처럼 없는 사진으로 본다(404).
     */
    @Transactional(readOnly = true)
    fun history(galleryId: Long, photoId: Long, userId: Long): List<PhotoRatingHistoryResponse> {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        val photo = photoRepository.findByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        return photoRatingHistoryRepository.findTop50ByPhotoIdOrderByIdDesc(photo.requiredId)
            .map { PhotoRatingHistoryResponse.from(it) }
    }
}
