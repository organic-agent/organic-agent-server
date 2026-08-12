package com.soma.wes.collab.service

import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.repository.CollabGuestRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.requireByIdAndCollabSessionId
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 하객이 링크로 **보는** 것들 — 첫 화면, 사진, 댓글. 남기는 쪽은 [CollabGuestService]다.
 */
@Service
class CollabGuestQueryService(
    private val collabSessionAccess: CollabSessionAccess,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabGuestRepository: CollabGuestRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
) {

    companion object {
        private const val UNKNOWN_NICKNAME = "알 수 없음"
    }

    @Transactional(readOnly = true)
    fun getLanding(collabToken: String): CollabLandingResponse {
        val access = collabSessionAccess.requireReadable(collabToken)

        return CollabLandingResponse(
            galleryTitle = access.gallery.title,
            photoCount = collabPhotoRepository.countByCollabSessionId(access.sessionId),
            writable = collabSessionAccess.isWritable(access),
        )
    }

    /**
     * 부부가 보여주는 사진들. 갤러리 전체가 아니라 세션에 담긴 것만이다.
     */
    @Transactional(readOnly = true)
    fun listPhotos(collabToken: String, guestToken: String?, page: Int, size: Int): CollabPhotoPageResponse {
        val access = collabSessionAccess.requireReadable(collabToken)

        return photoViewAssembler.toPage(
            sessionId = access.sessionId,
            page = page,
            size = size,
            guestId = collabSessionAccess.findGuest(access, guestToken)?.requiredId,
        )
    }

    /**
     * 사진 한 장에 달린 댓글. 최근 것이 위로 온다.
     */
    @Transactional(readOnly = true)
    fun listComments(
        collabToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        page: Int,
        size: Int,
    ): PageResponse<CollabCommentResponse> {
        val access = collabSessionAccess.requireReadable(collabToken)
        collabPhotoRepository.requireByIdAndCollabSessionId(collabPhotoId, access.sessionId)

        val found = collabPhotoCommentRepository.findAllByCollabPhotoIdOrderByIdDesc(
            collabPhotoId,
            PageRequests.of(page, size),
        )
        val nicknames = collabGuestRepository.findAllByIdIn(found.content.map { it.collabGuestId })
            .associate { it.requiredId to it.nickname }
        val myGuestId = collabSessionAccess.findGuest(access, guestToken)?.requiredId

        return PageResponse.of(
            found = found,
            contents = found.content.map {
                CollabCommentResponse(
                    commentId = it.requiredId,
                    nickname = nicknames[it.collabGuestId] ?: UNKNOWN_NICKNAME,
                    content = it.content,
                    createdAt = it.createdAt,
                    mine = myGuestId != null && it.isWrittenBy(myGuestId),
                )
            },
        )
    }
}
