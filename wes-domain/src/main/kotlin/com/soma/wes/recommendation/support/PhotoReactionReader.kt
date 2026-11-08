package com.soma.wes.recommendation.support

import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.recommendation.dto.PhotoReactionDto
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import org.springframework.stereotype.Component

/**
 * 갤러리의 반응을 사진당 한 줄([PhotoReactionDto])로 모은다 — 부부 별점, 담음, 하객 좋아요.
 *
 * 반응을 따로 저장하지 않고 원래 표에서 그때 합치는 이유: 별점·담기·좋아요는 각 기능이 자기 표에 쓴다. 반응 표를 따로 두면 그 쓰기
 * 경로가 모두 반응 표도 고쳐야 하고, 하나라도 빠지면 원래 표와 어긋난다(#391 ADR 0002). 조회는 갤러리 단위 세 번이다.
 * 호출자의 트랜잭션 안에서 돈다.
 */
@Component
class PhotoReactionReader(
    private val photoRatingRepository: PhotoRatingRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val collabPhotoLikeRepository: CollabPhotoLikeRepository,
) {

    fun read(galleryId: Long, selectionId: Long): Map<Long, PhotoReactionDto> {
        val ratings = photoRatingRepository.findAllByGalleryId(galleryId).associate { it.photoId to it.score }
        val selected = photoSelectionItemRepository.findAllBySelectionId(selectionId).mapTo(mutableSetOf()) { it.photoId }
        val guestLikes = collabPhotoLikeRepository.countByGalleryId(galleryId).associate { it.photoId to it.count.toInt() }

        return (ratings.keys + selected + guestLikes.keys).associateWith { photoId ->
            PhotoReactionDto(
                photoId = photoId,
                rating = ratings[photoId],
                selected = photoId in selected,
                guestLikes = guestLikes[photoId] ?: 0,
            )
        }
    }
}
