package com.soma.wes.retouch.support

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoStorage
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.dto.response.RetouchPhotoDetailResponse
import com.soma.wes.retouch.dto.response.RetouchPhotoResponse
import org.springframework.stereotype.Component

/**
 * 회차 항목을 화면이 그릴 수 있는 응답으로 만든다. 그리드와 회차 상세가 같은 규칙을 쓴다.
 */
@Component
class RetouchViewAssembler(
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val photoStorage: PhotoStorage,
) {

    /**
     * 여러 항목을 한 번에. 원본 사진과 별점을 항목 수만큼 따로 읽지 않는다.
     */
    fun toResponses(galleryId: Long, items: List<RetouchPhoto>): List<RetouchPhotoResponse> =
        assemble(galleryId, items) { item, photoResponse ->
            RetouchPhotoResponse.of(
                item = item,
                photo = photoResponse,
                annotationUrl = item.annotationKey?.let { photoStorage.presignView(it) },
            )
        }

    /**
     * 회차 상세용. 그리드 응답과 달리 결과 URL까지 서명해 전/후 비교를 지원한다.
     */
    fun toDetailResponses(galleryId: Long, items: List<RetouchPhoto>): List<RetouchPhotoDetailResponse> =
        assemble(galleryId, items) { item, photoResponse ->
            RetouchPhotoDetailResponse.of(
                item = item,
                photo = photoResponse,
                annotationUrl = item.annotationKey?.let { photoStorage.presignView(it) },
                resultUrl = item.resultKey?.let { photoStorage.presignView(it) },
            )
        }

    private fun <T> assemble(
        galleryId: Long,
        items: List<RetouchPhoto>,
        transform: (RetouchPhoto, PhotoResponse) -> T,
    ): List<T> {
        if (items.isEmpty()) {
            return emptyList()
        }

        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, items.map { it.photoId })
            .sortedWith(Photo.DISPLAY_ORDER)
        val photoResponses = photoViewAssembler.toResponses(photos)
        val itemsByPhotoId = items.associateBy { it.photoId }

        return photoResponses.mapNotNull { photoResponse ->
            itemsByPhotoId[photoResponse.photoId]?.let { item -> transform(item, photoResponse) }
        }
    }
}
