package com.soma.wes.collab.support

import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabPhotoResponse
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import org.springframework.stereotype.Component

/** 현재 세션의 공유 사진만 조립한다. 하객 화면과 부부 관리 화면이 같은 경계를 지난다. */
@Component
class CollabPhotoViewAssembler(
    private val membership: CollabPhotoMembership,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val likeRepository: CollabPhotoLikeRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val properties: StorageProperties,
) {
    fun count(session: CollabSession): Long = membership.count(session)

    fun toPage(
        session: CollabSession,
        page: Int,
        size: Int,
        participantId: Long? = null,
    ): CollabPhotoPageResponse {
        val sessionId = session.requiredId
        val allPhotoIds = membership.photoIds(session)
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 200)
        val from = (safePage.toLong() * safeSize).coerceAtMost(allPhotoIds.size.toLong()).toInt()
        val to = (from + safeSize).coerceAtMost(allPhotoIds.size)
        val pageIds = allPhotoIds.subList(from, to)
        val photosById = photoRepository.findAllById(pageIds).associateBy { it.requiredId }
        val photos = pageIds.mapNotNull(photosById::get)
        val responsesById = photos.map { it.requiredId }
            .zip(photoViewAssembler.toAnonymousResponses(photos))
            .toMap()

        val likeCounts = if (pageIds.isEmpty()) emptyMap() else
            likeRepository.countBySessionAndPhotoIdIn(sessionId, pageIds).associate { it.photoId to it.count }
        val commentCounts = if (pageIds.isEmpty()) emptyMap() else
            commentRepository.countBySessionAndPhotoIdIn(sessionId, pageIds).associate { it.photoId to it.count }
        val myLikes = if (participantId == null || pageIds.isEmpty()) emptySet() else
            likeRepository.findAllByCollabSessionIdAndPhotoIdInAndParticipantId(sessionId, pageIds, participantId)
                .mapTo(mutableSetOf()) { it.photoId }

        val contents = pageIds.mapNotNull { photoId ->
            val photo = responsesById[photoId] ?: return@mapNotNull null
            CollabPhotoResponse(
                photoId = photoId,
                photo = photo,
                likeCount = likeCounts[photoId] ?: 0,
                commentCount = commentCounts[photoId] ?: 0,
                liked = photoId in myLikes,
                metadata = com.soma.wes.photo.dto.response.PhotoMetadataResponse.from(photosById[photoId]?.metadata),
            )
        }
        val responsePage = PageResponse(
            page = safePage,
            size = safeSize,
            totalCount = allPhotoIds.size.toLong(),
            hasNext = to < allPhotoIds.size,
            contents = contents,
        )
        return CollabPhotoPageResponse.of(responsePage, properties.viewUrlTtl.seconds)
    }

    fun contains(session: CollabSession, photoId: Long): Boolean = membership.contains(session, photoId)
}
