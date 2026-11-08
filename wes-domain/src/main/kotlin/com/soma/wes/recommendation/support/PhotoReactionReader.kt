package com.soma.wes.recommendation.support

import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.recommendation.repository.AiRecommendationRepository
import com.soma.wes.recommendation.dto.PhotoReactionDto
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import org.springframework.stereotype.Component

/**
 * 갤러리의 반응을 사진당 한 줄([PhotoReactionDto])로 모은다 — 부부 별점, 담음, 하객 좋아요, 보여줬는데 안 담음.
 *
 * 반응을 따로 저장하지 않고 원래 표에서 그때 합치는 이유: 별점·담기·좋아요는 각 기능이 자기 표에 쓴다. 반응 표를 따로 두면 그 쓰기
 * 경로가 모두 반응 표도 고쳐야 하고, 하나라도 빠지면 원래 표와 어긋난다(#391 ADR 0002). 조회는 갤러리·선택 앨범 단위 네 번이다.
 * 호출자의 트랜잭션 안에서 돈다.
 */
@Component
class PhotoReactionReader(
    private val photoRatingRepository: PhotoRatingRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val collabPhotoLikeRepository: CollabPhotoLikeRepository,
    private val aiRecommendationRepository: AiRecommendationRepository,
) {

    fun read(galleryId: Long, selectionId: Long): Map<Long, PhotoReactionDto> {
        val ratings = photoRatingRepository.findAllByGalleryId(galleryId).associateBy { it.photoId }
        val selected = photoSelectionItemRepository.findAllBySelectionId(selectionId).mapTo(mutableSetOf()) { it.photoId }
        val guestLikes = collabPhotoLikeRepository.countByGalleryId(galleryId).associate { it.photoId to it.count.toInt() }
        // 지금 보이는 추천도 센다 — 이 읽기는 다음 추천을 계산할 때 돌므로, 재추천을 요청한 것 자체가 지금 보이는 사진을 안 담고 넘긴 것이다.
        val ignored = aiRecommendationRepository.findAllBySelectionId(selectionId)
            .filter { shown -> shown.rejectedAt == null }
            .filter { shown -> ratings[shown.photoId]?.updatedAt?.let { ratedAt -> shown.presentedAt.isAfter(ratedAt) } ?: true }
            .groupingBy { it.photoId }
            .eachCount()

        return (ratings.keys + selected + guestLikes.keys + ignored.keys).associateWith { photoId ->
            PhotoReactionDto(
                photoId = photoId,
                rating = ratings[photoId]?.score,
                selected = photoId in selected,
                guestLikes = guestLikes[photoId] ?: 0,
                ignored = ignored[photoId] ?: 0,
            )
        }
    }
}
