package com.soma.wes.collab.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.dto.request.CollabPhotoIdsRequest
import com.soma.wes.collab.repository.CollabParticipantRepository
import com.soma.wes.collab.repository.CollabSessionPhotoRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.support.CollabPhotoMembership
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest.PhotoScope.Type as PhotoScopeType
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
    private val detailRepository: DetailFolderRepository,
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
    private val participants: CollabParticipantRepository,
) {
    @Transactional
    fun open(galleryId: Long, userId: Long, request: OpenCollabSessionRequest): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        val sources = listOf(request.conceptFolderId != null, request.photoIds.isNotEmpty(), request.scope != null)
        if (sources.count { it } > 1) {
            throw CollabException(CollabErrorCode.INVALID_SELECTION_SOURCE)
        }
        // conceptFolderId는 옛 요청 모양이다. 컨셉 하나를 범위로 보낸 것과 같다.
        val scope = request.scope ?: request.conceptFolderId?.let {
            OpenCollabSessionRequest.PhotoScope(PhotoScopeType.CONCEPT_FOLDERS, conceptFolderIds = listOf(it))
        }
        val photoIds = scope?.let { resolveScope(galleryId, it) }
            ?: validatePhotoIds(galleryId, request.photoIds, allowEmpty = true)
        val name = CollabSession.requireValidName(request.name)
        val now = ZonedDateTime.now(clock)
        val session = CollabSession.of(galleryId, name, tokenGenerator.generate()).apply {
            expiresAt = now.plusDays(LINK_TTL_DAYS)
            updateCover(request.coverTitle, request.coverAuthor)
            request.includeAllAlbums?.let { includeAllAlbums = it }
        }
        sessionRepository.saveAndFlush(session)
        saveMemberships(session, photoIds, now)
        activityRecorder.recordGallery(galleryId)
        return toResponse(session)
    }

    /** 이미 담긴 사진은 그대로 두고 새 사진만 추가한다. 이름·링크·분류 배정은 바꾸지 않는다. */
    @Transactional
    fun addPhotos(galleryId: Long, sessionId: Long, userId: Long, request: CollabPhotoIdsRequest): CollabSessionResponse {
        galleryAccessPolicy.requireCollabManager(galleryId, userId, writable = true)
        val ids = validatePhotoIds(galleryId, request.photoIds)
        val session = lockSession(galleryId, sessionId)
        val now = ZonedDateTime.now(clock)
        if (saveMemberships(session, ids, now) > 0) {
            session.photosChanged(now)
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

    private fun lockSession(galleryId: Long, sessionId: Long): CollabSession =
        sessionRepository.findWithLockByIdAndGalleryId(sessionId, galleryId)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)

    private fun validatePhotoIds(galleryId: Long, requested: List<Long>, allowEmpty: Boolean = false): List<Long> {
        if ((!allowEmpty && requested.isEmpty()) || requested.size > CollabPhotoIdsRequest.MAX_BATCH_SIZE || requested.any { it <= 0 }) {
            throw CollabException(CollabErrorCode.INVALID_PHOTO_IDS)
        }
        val ids = requested.distinct()
        if (lockShareable(galleryId, ids).size != ids.size) {
            throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }
        return ids
    }

    /**
     * 범위를 만드는 순간의 사진 id로 푼다. 공유폴더는 원본 폴더와 따로 살아서, 이후 그 폴더가 바뀌어도 따라가지 않는다.
     * 직접 지정과 달리 휴지통·업로드 미완료 사진은 거절하지 않고 뺀다 — 부부가 고른 것은 사진 하나하나가 아니라 "이 폴더 전부"다.
     */
    private fun resolveScope(galleryId: Long, scope: OpenCollabSessionRequest.PhotoScope): List<Long> {
        val lists = mapOf(
            PhotoScopeType.CONCEPT_FOLDERS to scope.conceptFolderIds,
            PhotoScopeType.DETAIL_FOLDERS to scope.detailFolderIds,
            PhotoScopeType.SESSIONS to scope.sessionIds,
        )
        // 범위 종류에 맞는 목록 하나만 차 있어야 한다. ALL은 어떤 목록도 받지 않는다.
        val listed = lists.all { (type, ids) -> ids.isNotEmpty() == (type == scope.type) }
        if (!listed || lists.values.sumOf { it.size } > OpenCollabSessionRequest.PhotoScope.MAX_FOLDER_COUNT) {
            throw CollabException(CollabErrorCode.INVALID_PHOTO_SCOPE)
        }
        val candidates = when (scope.type) {
            PhotoScopeType.ALL -> memberships.findGalleryPhotoIds(galleryId)
            PhotoScopeType.CONCEPT_FOLDERS -> {
                val conceptIds = scope.conceptFolderIds.distinct()
                if (conceptRepository.countByGalleryIdAndIdIn(galleryId, conceptIds) != conceptIds.size.toLong()) {
                    throw FolderException(FolderErrorCode.CONCEPT_NOT_FOUND)
                }
                memberships.findConceptPhotoIds(galleryId, conceptIds)
            }
            PhotoScopeType.DETAIL_FOLDERS -> {
                val detailIds = scope.detailFolderIds.distinct()
                if (detailRepository.countByGalleryIdAndIdIn(galleryId, detailIds) != detailIds.size.toLong()) {
                    throw FolderException(FolderErrorCode.DETAIL_NOT_FOUND)
                }
                memberships.findDetailPhotoIds(galleryId, detailIds)
            }
            PhotoScopeType.SESSIONS -> {
                val sessionIds = scope.sessionIds.distinct()
                val sessions = sessionRepository.findAllByGalleryIdAndIdIn(galleryId, sessionIds)
                if (sessions.size != sessionIds.size) throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
                photoMembership.photoIds(sessions).values.flatten()
            }
        }
        return lockShareable(galleryId, candidates.distinct())
    }

    private fun lockShareable(galleryId: Long, ids: List<Long>): List<Long> =
        if (ids.isEmpty()) emptyList() else memberships.lockShareablePhotoIds(galleryId, ids)

    /** 새로 담긴 사진 수를 돌려준다. */
    private fun saveMemberships(session: CollabSession, ids: List<Long>, now: ZonedDateTime): Int =
        if (ids.isEmpty()) 0 else memberships.insertMemberships(session.requiredId, session.galleryId, ids, now)

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
        participants.countByCollabSessionId(session.requiredId),
    )
    companion object {
        /** 와이어프레임의 게스트 링크 유효기간. */
        private const val LINK_TTL_DAYS = 7L
    }
}
