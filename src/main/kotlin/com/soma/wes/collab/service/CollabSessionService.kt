package com.soma.wes.collab.service

import com.soma.wes.category.exception.CategoryErrorCode
import com.soma.wes.category.exception.CategoryException
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.repository.requireByIdAndGalleryId
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.trash.service.ProductChildTrashService
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CollabSessionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val sessionRepository: CollabSessionRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val productChildTrashService: ProductChildTrashService,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val urlResolver: CollabLinkResolver,
    private val tokenGenerator: SecureTokenGenerator,
    private val clock: Clock,
) {
    @Transactional
    fun open(galleryId: Long, userId: Long, request: OpenCollabSessionRequest): CollabSessionResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        galleryRepository.requireWithLockById(galleryId)
        val concept = conceptRepository.findByIdAndGalleryId(request.conceptFolderId, galleryId)
            ?: throw CategoryException(CategoryErrorCode.CONCEPT_NOT_FOUND)

        val existing = sessionRepository.findByConceptFolderId(concept.requiredId)
        if (existing != null) {
            if (existing.isRevoked) existing.republish(tokenGenerator.generate())
            existing.rename(request.name)
            return toResponse(existing)
        }
        val session = sessionRepository.save(
            CollabSession.of(galleryId, concept.requiredId, request.name, tokenGenerator.generate()),
        )
        return toResponse(session)
    }

    @Transactional
    fun rename(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: RenameCollabSessionRequest,
    ): CollabSessionResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val session = sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        session.rename(request.name)
        return toResponse(session)
    }

    @Transactional
    fun republish(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        galleryRepository.requireWithLockById(galleryId)
        val session = sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        session.republish(tokenGenerator.generate())
        return toResponse(session)
    }

    @Transactional
    fun revoke(galleryId: Long, sessionId: Long, userId: Long) {
        galleryAccessPolicy.requireManager(galleryId, userId)
        galleryRepository.requireWithLockById(galleryId)
        sessionRepository.requireByIdAndGalleryId(sessionId, galleryId).revoke(ZonedDateTime.now(clock))
    }

    @Transactional
    fun deleteComment(galleryId: Long, sessionId: Long, commentId: Long, userId: Long) {
        galleryAccessPolicy.requireManager(galleryId, userId)
        sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        val comment = commentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        if (comment.collabSessionId != sessionId || !productChildTrashService.deleteUserComment(sessionId, commentId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)
        }
    }

    private fun toResponse(session: CollabSession): CollabSessionResponse = CollabSessionResponse.of(
        session,
        urlResolver.resolve(session.collabToken),
        photoViewAssembler.count(session.conceptFolderId),
    )
}
