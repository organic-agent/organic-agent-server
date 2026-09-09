package com.soma.wes.photo.support

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.photo.service.port.PhotoStorage
import org.springframework.stereotype.Component

/**
 * 사진 엔티티를 화면이 그릴 수 있는 응답으로 만든다.
 */
@Component
class PhotoViewAssembler(
    private val photoStorage: PhotoStorage,
    private val photoRatingRepository: PhotoRatingRepository,
) {

    fun toResponse(photo: Photo): PhotoResponse = PhotoResponse.of(
        photo = photo,
        viewUrl = viewUrlOf(photo),
        score = scoreOf(photo),
    )

    /**
     * 여러 장을 한 번에. 별점을 사진 수만큼 따로 읽지 않는다.
     * 한 페이지가 200장이면 [toResponse]를 반복하는 것만으로 질의가 200번 늘어난다.
     */
    fun toResponses(photos: List<Photo>): List<PhotoResponse> {
        val scores = scoresOf(photos)

        return photos.map {
            PhotoResponse.of(photo = it, viewUrl = viewUrlOf(it), score = scores[it.requiredId])
        }
    }

    /** [toResponses]가 쓴다. 사진이 없으면 IN 절이 빈 채로 나가지 않도록 먼저 끊는다. */
    private fun scoresOf(photos: List<Photo>): Map<Long, Int> {
        if (photos.isEmpty()) {
            return emptyMap()
        }

        return photoRatingRepository.findAllByPhotoIdIn(photos.map { it.requiredId })
            .associate { it.photoId to it.score }
    }

    /**
     * 협업 링크로 여는 화면이 쓴다. 별점을 아예 붙이지 않는다.
     */
    fun toAnonymousResponses(photos: List<Photo>): List<PhotoResponse> =
        photos.map { PhotoResponse.of(photo = it, viewUrl = viewUrlOf(it), score = null) }

    /**
     * 이 사진에 매겨진 점수. 아무도 매기지 않았으면 null이다.
     */
    fun scoreOf(photo: Photo): Int? = photoRatingRepository.findByPhotoId(photo.requiredId)?.score

    /**
     * 화면에 그릴 URL. 파생본이 있으면 그쪽이고, 없으면 원본이다.
     */
    fun viewUrlOf(photo: Photo): String? = presignIfUploaded(photo) { photoStorage.presignView(photo.viewKey) }

    /**
     * 원본을 원래 크기로 여는 URL. 상세 화면이 쓴다.
     */
    fun originalUrlOf(photo: Photo): String? =
        presignIfUploaded(photo) { photoStorage.presignOriginal(photo.storageKey) }

    /** PENDING 판정을 한곳에 둔다. 두 URL이 같은 규칙을 따라야 화면이 둘을 나란히 쓸 수 있다. */
    private fun presignIfUploaded(photo: Photo, presign: () -> String): String? =
        if (photo.status == PhotoStatus.PENDING) null else presign()
}
