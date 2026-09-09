package com.soma.wes.collab.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.category.exception.CategoryErrorCode
import com.soma.wes.category.exception.CategoryException
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.domain.CollabSessionPhoto
import com.soma.wes.collab.dto.request.CollabPhotoIdsRequest
import com.soma.wes.collab.repository.CollabSessionPhotoRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.support.CollabPhotoMembership
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.support.CollabLinkResolver
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
    private val conceptRepository: ConceptFolderRepository,
    private val sessionRepository: CollabSessionRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val productChildTrashService: ProductChildTrashService,
    private val urlResolver: CollabLinkResolver,
    private val tokenGenerator: SecureTokenGenerator,
    private val clock: Clock,
    private val memberships: CollabSessionPhotoRepository,
    private val likes: CollabPhotoLikeRepository,
    private val photoMembership: CollabPhotoMembership,
    private val activityRecorder: ActivityRecorder,
) {
    @Transactional
    fun open(galleryId: Long, userId: Long, request: OpenCollabSessionRequest): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        if (request.conceptFolderId != null && request.photoIds.isNotEmpty()) {
            throw CollabException(CollabErrorCode.INVALID_SELECTION_SOURCE)
        }
        val photoIds = validatePhotoIds(galleryId, request.photoIds, allowEmpty = true)
        val concept = request.conceptFolderId?.let { conceptId ->
            conceptRepository.findByIdAndGalleryId(conceptId, galleryId)
                ?: throw CategoryException(CategoryErrorCode.CONCEPT_NOT_FOUND)
        }
        val name = CollabSession.requireValidName(request.name)
        val now = ZonedDateTime.now(clock)
        val existing = concept?.let { sessionRepository.findByConceptFolderId(it.requiredId) }
        if (existing != null) {
            val session = lockSession(galleryId, existing.requiredId)
            if (session.isRevoked || session.isExpiredAt(now)) session.republish(tokenGenerator.generate(), now.plusDays(LINK_TTL_DAYS))
            session.rename(name)
            session.updateCover(request.coverTitle, request.coverAuthor)
            request.includeAllAlbums?.let { session.includeAllAlbums = it }
            activityRecorder.recordGallery(galleryId)
            return toResponse(session)
        }
        val session = CollabSession.of(galleryId, concept?.requiredId, name, tokenGenerator.generate()).apply {
            expiresAt = now.plusDays(LINK_TTL_DAYS)
            updateCover(request.coverTitle, request.coverAuthor)
            request.includeAllAlbums?.let { includeAllAlbums = it }
        }
        sessionRepository.saveAndFlush(session)
        saveMemberships(session, photoIds)
        activityRecorder.recordGallery(galleryId)
        return toResponse(session)
    }

    /** 이미 담긴 사진은 그대로 두고 새 사진만 추가한다. 이름·링크·분류 배정은 바꾸지 않는다. */
    @Transactional
    fun addPhotos(galleryId: Long, sessionId: Long, userId: Long, request: CollabPhotoIdsRequest): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        val ids = validatePhotoIds(galleryId, request.photoIds)
        val session = lockSession(galleryId, sessionId)
        requireManual(session)
        val existing = memberships.findAllByCollabSessionIdAndPhotoIdIn(sessionId, ids).map { it.photoId }.toSet()
        val added = ids.filterNot { it in existing }
        if (added.isNotEmpty()) {
            saveMemberships(session, added)
            session.photosChanged(ZonedDateTime.now(clock))
            activityRecorder.recordGallery(galleryId)
        }
        return toResponse(session)
    }

    /** 이 폴더의 반응만 정리한다. 원본 사진과 다른 폴더의 구성·반응은 유지한다. */
    @Transactional
    fun removePhotos(galleryId: Long, sessionId: Long, userId: Long, request: CollabPhotoIdsRequest): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        val ids = validatePhotoIds(galleryId, request.photoIds)
        val session = lockSession(galleryId, sessionId)
        requireManual(session)
        val removed = memberships.findAllByCollabSessionIdAndPhotoIdIn(sessionId, ids).map { it.photoId }
        if (removed.isNotEmpty()) {
            likes.deleteAllByCollabSessionIdAndPhotoIdIn(sessionId, removed)
            commentRepository.deleteAllByCollabSessionIdAndPhotoIdIn(sessionId, removed)
            memberships.deleteMemberships(sessionId, removed)
            session.photosChanged(ZonedDateTime.now(clock))
            activityRecorder.recordGallery(galleryId)
        }
        return toResponse(session)
    }

    /** 현재 동적 구성을 고정하되 링크·참여자·반응·만료일은 유지한다. 재요청은 현재 수동 폴더를 반환한다. */
    @Transactional
    fun convertToManual(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        if (!sessionRepository.existsByIdAndGalleryId(sessionId, galleryId)) {
            throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
        }
        val snapshotIds = memberships.findSharedPhotos(listOf(sessionId)).map { it.photoId }
        val ids = if (snapshotIds.isEmpty()) emptyList() else {
            memberships.findWithLockByGalleryIdAndIdIn(galleryId, snapshotIds)
                .filter { it.status != PhotoStatus.PENDING }.map { it.requiredId }
        }
        val session = lockSession(galleryId, sessionId)
        if (session.conceptFolderId != null) {
            saveMemberships(session, ids)
            session.convertToManual(ZonedDateTime.now(clock))
            activityRecorder.recordGallery(galleryId)
            sessionRepository.flush()
        }
        return toResponse(session)
    }

    private fun lockSession(galleryId: Long, sessionId: Long): CollabSession =
        sessionRepository.findWithLockByIdAndGalleryId(sessionId, galleryId)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)

    private fun requireManual(session: CollabSession) {
        if (session.conceptFolderId != null) throw CollabException(CollabErrorCode.MANUAL_CONVERSION_REQUIRED)
    }

    private fun validatePhotoIds(galleryId: Long, requested: List<Long>, allowEmpty: Boolean = false): List<Long> {
        if ((!allowEmpty && requested.isEmpty()) || requested.size > CollabPhotoIdsRequest.MAX_BATCH_SIZE || requested.any { it <= 0 }) {
            throw CollabException(CollabErrorCode.INVALID_PHOTO_IDS)
        }
        val ids = requested.distinct()
        if (ids.isEmpty()) return ids
        val found = memberships.findWithLockByGalleryIdAndIdIn(galleryId, ids)
        if (found.size != ids.size || found.any { it.status == PhotoStatus.PENDING }) {
            throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }
        return ids
    }

    private fun saveMemberships(session: CollabSession, ids: List<Long>) {
        memberships.saveAllAndFlush(ids.map { photoId ->
            CollabSessionPhoto(collabSessionId = session.requiredId, galleryId = session.galleryId, photoId = photoId)
        })
    }

    @Transactional
    fun rename(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: RenameCollabSessionRequest,
    ): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        val session = lockSession(galleryId, sessionId)
        request.name?.let(session::rename)
        session.updateCover(request.coverTitle, request.coverAuthor)
        request.includeAllAlbums?.let { session.includeAllAlbums = it }
        activityRecorder.recordGallery(galleryId)
        return toResponse(session)
    }

    @Transactional
    fun republish(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        val session = lockSession(galleryId, sessionId)
        session.republish(tokenGenerator.generate(), ZonedDateTime.now(clock).plusDays(LINK_TTL_DAYS))
        activityRecorder.recordGallery(galleryId)
        return toResponse(session)
    }

    @Transactional
    fun revoke(galleryId: Long, sessionId: Long, userId: Long) {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = false)
        lockSession(galleryId, sessionId).revoke(ZonedDateTime.now(clock))
        activityRecorder.recordGallery(galleryId)
    }

    @Transactional
    fun deleteComment(galleryId: Long, sessionId: Long, commentId: Long, userId: Long) {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = false)
        lockSession(galleryId, sessionId)
        val comment = commentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        if (comment.collabSessionId != sessionId || !productChildTrashService.deleteUserComment(sessionId, commentId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)
        }
        activityRecorder.recordGallery(galleryId)
    }

    private fun toResponse(session: CollabSession): CollabSessionResponse = CollabSessionResponse.of(
        session,
        urlResolver.resolve(session.collabToken),
        photoMembership.count(session),
    )
    companion object {
        /** 와이어프레임의 게스트 링크 유효기간. */
        private const val LINK_TTL_DAYS = 7L
    }
}
