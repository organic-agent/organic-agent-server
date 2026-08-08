package com.soma.wes.photo.support

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.photo.service.PhotoStorage
import org.springframework.stereotype.Component

/**
 * 사진 엔티티를 화면이 그릴 수 있는 응답으로 만든다.
 *
 * 사진 목록·상세·클러스터·폴더가 모두 같은 규칙으로 URL과 별점을 붙여야 해서 한곳에 둔다. 규칙이
 * 여러 군데로 흩어지면 어느 한 곳만 PENDING 처리를 빠뜨려도 그 화면에서만 깨진 이미지가 나오고,
 * 별점도 한 화면에서만 늘 비어 보인다.
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
     * 여러 장을 한 번에. 별점을 사진 수만큼 따로 읽지 않는다 — 한 페이지가 200장이면
     * [toResponse]를 반복하는 것만으로 질의가 200번 늘어난다.
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
     * 이 사진에 매겨진 점수. 아무도 매기지 않았으면 null이다.
     *
     * 사진당 한 행이라 "내 점수"와 "남의 점수"가 없다 — 부부와 작가가 같은 한 칸을 나눠 쓴다.
     */
    fun scoreOf(photo: Photo): Int? = photoRatingRepository.findByPhotoId(photo.requiredId)?.score

    /**
     * 화면에 그릴 URL. 파생본이 있으면 그쪽이고, 없으면 원본이다.
     *
     * PENDING은 URL만 발급되고 실제 객체는 아직 없을 수 있다. URL을 주면 프론트의 `<img>`가
     * 깨진 이미지를 그리므로, 올라온 것이 확실한 사진에만 채운다.
     */
    fun viewUrlOf(photo: Photo): String? = presignIfUploaded(photo) { photoStorage.presignView(photo.viewKey) }

    /**
     * 원본을 원래 크기로 여는 URL. 상세 화면이 쓴다.
     *
     * [viewUrlOf]와 달리 파생본으로 갈아타지 않는다 — 파생본은 긴 변을 줄인 JPEG라 확대하면
     * 뭉개진다. 대신 원본은 HEIC일 수 있어 브라우저가 못 그리므로, 화면은 둘 다 받아 고른다.
     */
    fun originalUrlOf(photo: Photo): String? =
        presignIfUploaded(photo) { photoStorage.presignOriginal(photo.storageKey) }

    /** PENDING 판정을 한곳에 둔다. 두 URL이 같은 규칙을 따라야 화면이 둘을 나란히 쓸 수 있다. */
    private fun presignIfUploaded(photo: Photo, presign: () -> String): String? =
        if (photo.status == PhotoStatus.PENDING) null else presign()
}
