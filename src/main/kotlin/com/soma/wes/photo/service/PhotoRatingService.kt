package com.soma.wes.photo.service

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
 *
 * 매기는 사람을 가리지 않는다([GalleryAccessPolicy.requirePhotographerOrCouple]). 부부 두 사람은
 * 물론이고 작가도 추천작에 별을 달 수 있고, 셋이 같은 한 칸을 나눠 쓴다 — 마지막에 매긴 사람이
 * 덮어쓰고 그 사람만 [PhotoRating.ratedBy]에 남는다. 부부에게는 갤러리가 열려 있고 마감 전이어야
 * 한다는 조건이 붙지만 작가에게는 없다.
 */
@Service
class PhotoRatingService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val photoRatingRepository: PhotoRatingRepository,
) {

    /**
     * 별점을 매긴다. 같은 사진에 다시 부르면 덮어쓴다.
     *
     * 사진 행을 잠그고 시작한다. 별점은 사진당 한 행이라 "없으면 만들고 있으면 고친다"인데,
     * 신랑과 신부가 같은 사진에 동시에 별을 달면 둘 다 "아직 없다"를 읽어 각자 INSERT 하고
     * 유니크 제약에 걸린 한쪽이 500으로 실패한다.
     */
    @Transactional
    fun rate(galleryId: Long, photoId: Long, userId: Long, request: RatePhotoRequest): PhotoRatingResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val photo = photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        val rating = photoRatingRepository.findByPhotoId(photo.requiredId)
            ?: return PhotoRatingResponse.from(
                photoRatingRepository.save(PhotoRating.of(photo.requiredId, request.score, userId)),
            )

        rating.rate(request.score, userId)
        return PhotoRatingResponse.from(rating)
    }

    /**
     * 별점을 지운다. 매긴 적 없는 사진에도 성공한다.
     *
     * 앨범에서 사진을 뺄 때와 다르다 — 그쪽은 목록에서 한 장이 빠지는 일이라 없는 것을 빼면
     * 화면만 지운 것이 되지만, 이쪽은 "점수 없음"이라는 한 상태로 만드는 일이고 이미 그 상태다.
     * 대신 사진 자체가 이 갤러리의 것이 아니면 404다 — 그때는 화면이 잘못된 것을 가리키고 있다.
     */
    @Transactional
    fun clear(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val photo = photoRepository.findByIdAndGalleryId(photoId, galleryId)
            ?: throw PhotoException(PhotoErrorCode.PHOTO_NOT_FOUND)

        photoRatingRepository.deleteByPhotoId(photo.requiredId)
    }
}
