package com.soma.wes.retouch.support

import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import org.springframework.stereotype.Component

/**
 * 다른 도메인이 보정 결과를 읽을 때의 입구. 선택 앨범(selection)이 보정본을 담고 그릴 때 쓴다.
 */
@Component
class RetouchResultLoader(
    private val retouchPhotoRepository: RetouchPhotoRepository,
) {

    /**
     * 선택 앨범에 담을 수 있는 보정 항목인지 검증한다. 잘못된 짝이 하나라도 섞이면 전체를
     * 거절한다.
     *
     * [retouchPhotoIdByPhotoId]는 원본 photoId → 담으려는 retouchPhotoId다. 각 짝에 대해
     * 이 갤러리의 항목인지, 항목의 원본이 photoId와 일치하는지, 작가의 결과가 있는지를 본다 —
     * 같은 retouchPhotoId를 다른 원본에 두 번 물리는 요청도 일치 검사가 걸러낸다.
     */
    fun validateSelectable(galleryId: Long, retouchPhotoIdByPhotoId: Map<Long, Long>) {
        if (retouchPhotoIdByPhotoId.isEmpty()) {
            return
        }

        val itemsById = retouchPhotoRepository
            .findAllByGalleryIdAndIdIn(galleryId, retouchPhotoIdByPhotoId.values.toSet())
            .associateBy { it.requiredId }

        retouchPhotoIdByPhotoId.forEach { (photoId, retouchPhotoId) ->
            val item = itemsById[retouchPhotoId]
                ?: throw RetouchException(RetouchErrorCode.RETOUCH_PHOTO_NOT_IN_GALLERY)
            if (item.photoId != photoId) {
                throw RetouchException(RetouchErrorCode.RETOUCH_PHOTO_MISMATCH)
            }
            if (!item.hasResult) {
                throw RetouchException(RetouchErrorCode.RESULT_NOT_UPLOADED)
            }
        }
    }

    /**
     * 화면에 그릴 보정 항목들을 읽는다. 검증 없는 조회다 — 저장된 참조를 다시 그리는 길이라,
     * 갤러리 스코프만 걸면 충분하다.
     */
    fun findResults(galleryId: Long, retouchPhotoIds: Collection<Long>): List<RetouchPhoto> {
        if (retouchPhotoIds.isEmpty()) {
            return emptyList()
        }

        return retouchPhotoRepository.findAllByGalleryIdAndIdIn(galleryId, retouchPhotoIds)
    }
}
