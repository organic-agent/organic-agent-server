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
 * 전부 [GalleryAccessPolicy.requireViewer]를 지난다
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
        val collabSession = collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId)

        return CollabSessionResponse.of(
            session = collabSession,
            collabUrl = urlResolver.resolve(collabSession.collabToken),
            photoCount = collabPhotoRepository.countByCollabSessionId(collabSession.requiredId),
        )
    }

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
