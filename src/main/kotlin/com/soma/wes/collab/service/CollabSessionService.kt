package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabPhoto
import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.repository.requireByIdAndGalleryId
import com.soma.wes.collab.support.CollabPhotoLoader
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.SecureTokenGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime


/**
 * 부부가 협업 세션을 만들고 고친다. 읽는 쪽은 [CollabSessionQueryService]다.
 */
@Service
class CollabSessionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val collabSessionRepository: CollabSessionRepository,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val photoLoader: CollabPhotoLoader,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val urlResolver: CollabLinkResolver,
    private val tokenGenerator: SecureTokenGenerator,
    private val clock: Clock,
) {

    companion object {
        private const val EDIT_RESULT_PAGE_SIZE = 200
    }

    @Transactional
    fun open(galleryId: Long, userId: Long, request: OpenCollabSessionRequest): CollabSessionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        // 저장보다 확인이 먼저다. 폴더가 잘못됐는데 세션만 만들어두면, 부부에게는 실패로 보이는
        // 요청이 이름 없는 빈 링크를 하나 남긴다.
        val photos = request.folderId?.let { photoLoader.loadFromFolder(galleryId, it) }
        val session = collabSessionRepository.save(
            CollabSession.of(galleryId, request.name, tokenGenerator.generate()),
        )

        if (photos != null) {
            collabPhotoRepository.saveAll(
                photos.map { CollabPhoto(collabSessionId = session.requiredId, photoId = it.requiredId) },
            )
        }
        return toResponse(session)
    }

    @Transactional
    fun rename(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: RenameCollabSessionRequest,
    ): CollabSessionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val session = collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        session.rename(request.name)

        return toResponse(session)
    }

    @Transactional
    fun republish(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        val session = collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        session.republish(tokenGenerator.generate())

        return toResponse(session)
    }

    private fun toResponse(session: CollabSession): CollabSessionResponse = CollabSessionResponse.of(
        session = session,
        collabUrl = urlResolver.resolve(session.collabToken),
        photoCount = collabPhotoRepository.countByCollabSessionId(session.requiredId),
    )

    @Transactional
    fun revoke(galleryId: Long, sessionId: Long, userId: Long) {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId).revoke(ZonedDateTime.now(clock))
    }

    @Transactional
    fun addPhotos(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: AddCollabPhotosRequest,
    ): CollabPhotoPageResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        val photos = photoLoader.load(galleryId, request.photoIds)

        val alreadyAdded = collabPhotoRepository.findAllByCollabSessionId(sessionId)
            .map { it.photoId }
            .toSet()
        if (photos.any { it.requiredId in alreadyAdded }) {
            throw CollabException(CollabErrorCode.PHOTO_ALREADY_ADDED)
        }

        collabPhotoRepository.saveAll(
            photos.map { CollabPhoto(collabSessionId = sessionId, photoId = it.requiredId) },
        )

        return photoViewAssembler.toPage(sessionId, page = 0, size = EDIT_RESULT_PAGE_SIZE)
    }

    @Transactional
    fun removePhotos(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: RemoveCollabPhotosRequest,
    ): CollabPhotoPageResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        if (request.photoIds.isEmpty()) {
            throw CollabException(CollabErrorCode.EMPTY_PHOTO_IDS)
        }

        collabPhotoRepository.deleteAllByCollabSessionIdAndPhotoIdIn(
            sessionId,
            request.photoIds.toSet(),
        )

        return photoViewAssembler.toPage(sessionId, page = 0, size = EDIT_RESULT_PAGE_SIZE)
    }

    @Transactional
    fun deleteComment(galleryId: Long, sessionId: Long, commentId: Long, userId: Long) {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        collabSessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        val comment = collabPhotoCommentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        // 다른 세션의 댓글을 이 세션 경로로 지우지 못하게 한다. 같은 갤러리의 옆 세션도 남이다 —
        // 부모님께 물은 글을 친구들 링크의 화면에서 지울 수 있으면 안 된다.
        collabPhotoRepository.findByIdAndCollabSessionId(comment.collabPhotoId, sessionId)
            ?: throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)

        collabPhotoCommentRepository.delete(comment)
    }
}
