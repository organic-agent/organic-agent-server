package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.repository.requireByIdAndGalleryId
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.gallery.support.GalleryAccessPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 부부와 담당 작가가 협업 세션을 읽는다. 쓰는 쪽은 [CollabSessionService]다.
 *
 * 전부 [GalleryAccessPolicy.requireViewer]를 지난다 — 작가도 하객 반응을 보고 어느 사진부터
 * 보정할지 정할 수 있어야 하고, 마감된 뒤에도 그 결과는 남아 있어야 한다. 보는 것은 고르는
 * 것이 아니므로 닫힌 갤러리가 빈 갤러리로 보여서는 안 된다.
 *
 * 같은 것을 링크로 보는 경로는 [CollabGuestQueryService]다.
 */
@Service
class CollabSessionQueryService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val collabSessionRepository: CollabSessionRepository,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val urlResolver: CollabLinkResolver,
) {

    /** 관리 화면의 링크 목록. 작가도 본다 — 어느 묶음을 누구에게 물었는지 보고 보정 순서를 정한다. */
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<CollabSessionResponse> {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val sessions = collabSessionRepository.findAllByGalleryIdOrderByCreatedAtDesc(galleryId)
        if (sessions.isEmpty()) {
            return emptyList()
        }

        // 세션마다 세면 목록 길이만큼 질의가 늘어난다. 한 번에 읽어 나눈다.
        val photoCounts = collabPhotoRepository
            .countByCollabSessionIdIn(sessions.map { it.requiredId })
            .associate { it.collabSessionId to it.count }

        return sessions.map {
            CollabSessionResponse.of(
                session = it,
                collabUrl = urlResolver.resolve(it.collabToken),
                photoCount = photoCounts[it.requiredId] ?: 0L,
            )
        }
    }

    /** 링크를 복사하는 화면과 결과 화면이 함께 부른다. */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        return toResponse(collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId))
    }

    /**
     * [get]이 쓴다. 한 건에 질의 하나다 — 목록은 [list]가 배치로 세므로 여기를 부르지 않는다.
     */
    private fun toResponse(session: CollabSession): CollabSessionResponse = CollabSessionResponse.of(
        session = session,
        collabUrl = urlResolver.resolve(session.collabToken),
        photoCount = collabPhotoRepository.countByCollabSessionId(session.requiredId),
    )

    /**
     * 하객이 무엇을 어떻게 봤는지.
     *
     * `myReaction`은 늘 비어 있다 — 부부와 작가는 하객이 아니라 반응을 남기지 않는다.
     */
    @Transactional(readOnly = true)
    fun listPhotos(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        page: Int,
        size: Int,
    ): CollabPhotoPageResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId)

        return photoViewAssembler.toPage(sessionId, page, size)
    }
}
