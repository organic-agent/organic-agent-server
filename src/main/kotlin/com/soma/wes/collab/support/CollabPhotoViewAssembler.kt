package com.soma.wes.collab.support

import com.soma.wes.collab.domain.CollabPhoto
import com.soma.wes.collab.domain.CollabReaction
import com.soma.wes.collab.dto.response.CollabPhotoResponse
import com.soma.wes.collab.dto.response.CollabReactionCountResponse
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoVoteRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import org.springframework.stereotype.Component

/**
 * 협업 사진을 화면이 그릴 수 있는 응답으로 만든다.
 *
 * 하객 화면과 부부의 결과 화면이 같은 것을 본다 — 사진, 반응 수, 댓글 수. 다른 것은 "내가 누른
 * 반응" 하나뿐이라 한곳에서 만든다. 나누면 한쪽만 집계 기준이 바뀌어, 부부가 보는 수와 하객이
 * 보는 수가 달라지는 날이 온다.
 *
 * **집계는 사진 수와 무관하게 세 번만 읽는다.** 사진마다 세면 한 페이지가 수십 장일 때 질의가
 * 수십 번 늘어난다.
 */
@Component
class CollabPhotoViewAssembler(
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val voteRepository: CollabPhotoVoteRepository,
    private val commentRepository: CollabPhotoCommentRepository,
) {

    /**
     * [guestId]가 null이면 하객이 아닌 사람(부부·작가)이 보는 것이라 `myReaction`이 비어 나간다.
     */
    fun toResponses(collabPhotos: List<CollabPhoto>, guestId: Long?): List<CollabPhotoResponse> {
        if (collabPhotos.isEmpty()) {
            return emptyList()
        }

        val collabPhotoIds = collabPhotos.map { it.requiredId }
        val reactions = reactionsOf(collabPhotoIds)
        val commentCounts = commentRepository.countByCollabPhotoIdIn(collabPhotoIds)
            .associate { it.collabPhotoId to it.count }
        val myReactions = myReactionsOf(collabPhotoIds, guestId)
        val photos = photosOf(collabPhotos)

        return collabPhotos.mapNotNull { collabPhoto ->
            val photo = photos[collabPhoto.photoId] ?: return@mapNotNull null
            CollabPhotoResponse(
                collabPhotoId = collabPhoto.requiredId,
                photo = photo,
                reactions = reactions[collabPhoto.requiredId] ?: CollabReactionCountResponse.NONE,
                commentCount = commentCounts[collabPhoto.requiredId] ?: 0,
                myReaction = myReactions[collabPhoto.requiredId],
            )
        }
    }

    /** 반응이 하나도 없는 사진은 집계 결과에 아예 없다. 그 자리는 호출부가 0으로 채운다. */
    private fun reactionsOf(collabPhotoIds: List<Long>): Map<Long, CollabReactionCountResponse> =
        voteRepository.countByReaction(collabPhotoIds)
            .groupBy { it.collabPhotoId }
            .mapValues { (_, rows) ->
                val byReaction = rows.associate { it.reaction to it.count }
                CollabReactionCountResponse(
                    good = byReaction[CollabReaction.GOOD] ?: 0,
                    soso = byReaction[CollabReaction.SOSO] ?: 0,
                    bad = byReaction[CollabReaction.BAD] ?: 0,
                )
            }

    private fun myReactionsOf(collabPhotoIds: List<Long>, guestId: Long?): Map<Long, CollabReaction> {
        if (guestId == null) {
            return emptyMap()
        }

        return voteRepository.findAllByCollabPhotoIdInAndCollabGuestId(collabPhotoIds, guestId)
            .associate { it.collabPhotoId to it.reaction }
    }

    /**
     * 사진을 서명 URL이 붙은 응답으로 바꿔 사진 id로 찾을 수 있게 둔다.
     *
     * **별점을 붙이지 않는다**([PhotoViewAssembler.toAnonymousResponses]). 별점은 부부와 작가가
     * 고르며 서로에게 남기는 표시라, 하객이 보면 사진이 아니라 그 사람들의 판단이 새어 나간다.
     * 부부의 결과 화면에서도 빼는 이유는 그 화면이 "하객이 어떻게 봤는가"를 읽는 자리이기
     * 때문이다 — 자기 별점은 갤러리 그리드에 이미 있다.
     */
    private fun photosOf(collabPhotos: List<CollabPhoto>): Map<Long, PhotoResponse> {
        val photos: List<Photo> = photoRepository.findAllById(collabPhotos.map { it.photoId })

        return photos.map { it.requiredId }
            .zip(photoViewAssembler.toAnonymousResponses(photos))
            .toMap()
    }
}
