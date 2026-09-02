package com.soma.wes.collab.support

import com.soma.wes.category.repository.DetailFolderRepository
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabPhotoResponse
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import org.springframework.stereotype.Component

/** 컨셉폴더의 현재 배정을 동적으로 읽어 공유 화면을 만든다. 사진 복사 행은 만들지 않는다. */
@Component
class CollabPhotoViewAssembler(
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val likeRepository: CollabPhotoLikeRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val properties: StorageProperties,
) {
    fun count(conceptFolderId: Long): Long = photoIds(conceptFolderId).size.toLong()

    fun toPage(
        sessionId: Long,
        conceptFolderId: Long,
        page: Int,
        size: Int,
        guestId: Long? = null,
    ): CollabPhotoPageResponse {
        val allPhotoIds = photoIds(conceptFolderId)
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 200)
        val from = (safePage * safeSize).coerceAtMost(allPhotoIds.size)
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
        val myLikes = if (guestId == null || pageIds.isEmpty()) emptySet() else
            likeRepository.findAllByCollabSessionIdAndPhotoIdInAndCollabGuestId(sessionId, pageIds, guestId)
                .mapTo(mutableSetOf()) { it.photoId }

        val contents = pageIds.mapNotNull { photoId ->
            val photo = responsesById[photoId] ?: return@mapNotNull null
            CollabPhotoResponse(
                photoId = photoId,
                photo = photo,
                likeCount = likeCounts[photoId] ?: 0,
                commentCount = commentCounts[photoId] ?: 0,
                liked = photoId in myLikes,
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

    fun contains(conceptFolderId: Long, photoId: Long): Boolean = photoId in photoIds(conceptFolderId)

    private fun photoIds(conceptFolderId: Long): List<Long> {
        val detailIds = detailRepository.findAllByConceptFolderIdOrderBySortOrderAscIdAsc(conceptFolderId)
            .map { it.requiredId }
        if (detailIds.isEmpty()) return emptyList()
        return assignmentRepository.findAllByDetailFolderIdIn(detailIds)
            .map { it.photoId }
            .distinct()
    }
}
