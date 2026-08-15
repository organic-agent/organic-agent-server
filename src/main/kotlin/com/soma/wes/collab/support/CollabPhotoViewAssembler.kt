package com.soma.wes.collab.support

import com.soma.wes.collab.domain.CollabPhoto
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabPhotoResponse
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import org.springframework.stereotype.Component


/**
 * 협업 사진 한 페이지를 화면이 그릴 수 있는 응답으로 만든다.
 */
@Component
class CollabPhotoViewAssembler(
    private val collabPhotoRepository: CollabPhotoRepository,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val likeRepository: CollabPhotoLikeRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val properties: StorageProperties,
) {

    /**
     * 세션에 담긴 사진 한 페이지. 부부·작가의 결과 화면과 하객 화면이 같은 것을 쓴다.
     */
    fun toPage(sessionId: Long, page: Int, size: Int, guestId: Long? = null): CollabPhotoPageResponse {
        val found = collabPhotoRepository.findAllByCollabSessionIdOrderByIdAsc(
            sessionId,
            PageRequests.of(page, size),
        )

        return CollabPhotoPageResponse.of(
            page = PageResponse.of(found, toResponses(found.content, guestId)),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 집계는 사진 수와 무관하게 세 번만 읽는다. 사진마다 세면 한 페이지가 수십 장일 때
     * 질의가 수십 번 늘어난다.
     */
    private fun toResponses(collabPhotos: List<CollabPhoto>, guestId: Long?): List<CollabPhotoResponse> {
        if (collabPhotos.isEmpty()) {
            return emptyList()
        }

        val collabPhotoIds = collabPhotos.map { it.requiredId }
        // 좋아요가 하나도 없는 사진은 집계 결과에 아예 없다. 그 자리를 0으로 채운다.
        val likeCounts = likeRepository.countByCollabPhotoIdIn(collabPhotoIds)
            .associate { it.collabPhotoId to it.count }
        val commentCounts = commentRepository.countByCollabPhotoIdIn(collabPhotoIds)
            .associate { it.collabPhotoId to it.count }
        val myLikes = myLikesOf(collabPhotoIds, guestId)
        val photos = photosOf(collabPhotos)

        return collabPhotos.mapNotNull { collabPhoto ->
            val photo = photos[collabPhoto.photoId] ?: return@mapNotNull null
            CollabPhotoResponse(
                collabPhotoId = collabPhoto.requiredId,
                photo = photo,
                likeCount = likeCounts[collabPhoto.requiredId] ?: 0,
                commentCount = commentCounts[collabPhoto.requiredId] ?: 0,
                liked = collabPhoto.requiredId in myLikes,
            )
        }
    }

    private fun myLikesOf(collabPhotoIds: List<Long>, guestId: Long?): Set<Long> {
        if (guestId == null) {
            return emptySet()
        }

        return likeRepository.findAllByCollabPhotoIdInAndCollabGuestId(collabPhotoIds, guestId)
            .map { it.collabPhotoId }
            .toSet()
    }

    /**
     * 사진을 서명 URL이 붙은 응답으로 바꿔 사진 id로 찾을 수 있게 둔다.
     *
     * 휴지통 사진은 `findAllById`가 걸러내고 호출부의 `mapNotNull`이 그 자리를 조용히 접는다.
     * TODO: 페이지 총계는 `collab_photos` 행 수라, 휴지통에 든 사진만큼 실제 표시 수보다
     *   크게 나온다. 페이징 쿼리가 photos와 조인해 세도록 고치면 맞지만 MVP에서는 넘어간다.
     */
    private fun photosOf(collabPhotos: List<CollabPhoto>): Map<Long, PhotoResponse> {
        val photos: List<Photo> = photoRepository.findAllById(collabPhotos.map { it.photoId })

        return photos.map { it.requiredId }
            .zip(photoViewAssembler.toAnonymousResponses(photos))
            .toMap()
    }
}
