package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabPhoto
import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.support.CollabLinkAssembler
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabPaging
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 부부와 담당 작가가 다루는 협업 세션. 링크를 열고 끊고, 하객에게 보여줄 사진을 담고, 결과를 본다.
 *
 * 역할이 경로마다 다르다. **세션을 열고 사진을 담는 것은 부부의 일**이라
 * [GalleryAccessPolicy.requireCouple]을 지난다 — 하객에게 무엇을 물을지는 고르는 과정의 일부이고,
 * 작가가 고객 대신 물어볼 수는 없다. 반면 **읽는 것은 둘 다**
 * ([GalleryAccessPolicy.requireViewer]) 필요하다. 작가도 하객 반응을 보고 어느 사진부터 보정할지
 * 정할 수 있어야 하고, 마감된 뒤에도 그 결과는 남아 있어야 한다.
 *
 * 고치는 경로는 갤러리 행을 잠그고 시작한다([lockGallery]). 신랑과 신부가 동시에 담는 일이
 * 실제로 일어나는데, 잠그지 않으면 "세션이 없으면 만든다"가 겹쳐 한쪽이 유니크 제약에 걸려
 * 실패하고, 같은 사진을 동시에 담으면 중복 확인이 둘 다 통과한다.
 */
@Service
class CollabSessionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val collabSessionRepository: CollabSessionRepository,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoViewAssembler: CollabPhotoViewAssembler,
    private val photoRepository: PhotoRepository,
    private val tokenGenerator: SecureTokenGenerator,
    private val linkAssembler: CollabLinkAssembler,
    private val properties: StorageProperties,
    private val clock: Clock,
) {

    companion object {
        /**
         * 사진을 담거나 뺀 뒤 곧바로 돌려주는 목록의 크기.
         *
         * 하객에게 물어보는 사진은 대개 수십 장이라 한 페이지에 다 들어온다. 그보다 많이 담은
         * 세션이라면 두 번째 페이지는 화면이 따로 부른다 — 여기서 전부 내려주면 사진 수만큼
         * 서명 URL을 만드느라 담기 요청이 느려진다.
         */
        private const val EDIT_RESULT_PAGE_SIZE = 200
    }

    /**
     * 세션을 열거나, 폐기한 링크를 새 토큰으로 다시 연다.
     *
     * 이미 열려 있으면 같은 링크를 그대로 돌려준다 — 버튼을 두 번 눌렀다고 하객이 들고 있는
     * 링크가 죽으면 안 된다. 폐기 상태일 때만 토큰이 바뀌고, 그때도 담긴 사진과 받은 의견은
     * 그대로 남는다.
     */
    @Transactional
    fun open(galleryId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val session = collabSessionRepository.findByGalleryId(galleryId)
            ?: return toResponse(
                collabSessionRepository.save(
                    CollabSession(galleryId = galleryId, shareToken = tokenGenerator.generate()),
                ),
            )

        if (session.isRevoked) {
            session.reissueToken(tokenGenerator.generate())
        }
        return toResponse(session)
    }

    /**
     * 링크를 거둬들인다. 단톡방에 잘못 올라갔을 때 할 수 있는 일이다.
     *
     * 담긴 사진과 받은 의견은 지우지 않는다. 부부가 끊고 싶은 것은 링크이지 하객이 남겨준
     * 말이 아니다. 다시 [open]하면 새 토큰이 나온다 — 같은 토큰을 되살리면 링크가 퍼진 그
     * 단톡방이 함께 되살아난다.
     */
    @Transactional
    fun revoke(galleryId: Long, userId: Long) {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val session = requireSession(galleryId)
        session.revoke(ZonedDateTime.now(clock))
    }

    /** 링크를 복사하는 화면과 결과 화면이 함께 부른다. 아직 열지 않았으면 404다. */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        return toResponse(requireSession(galleryId))
    }

    /**
     * 하객에게 보여줄 사진을 담는다.
     *
     * 확인이 저장보다 먼저다. 담을 수 있는 것만 담고 나머지를 버리면 화면에는 성공으로 보이고,
     * 어느 사진이 빠졌는지는 아무도 모른다 — 선택 앨범이 같은 판단을 한다.
     */
    @Transactional
    fun addPhotos(galleryId: Long, userId: Long, request: AddCollabPhotosRequest): CollabPhotoPageResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val session = requireSession(galleryId)
        val photos = loadAddablePhotos(galleryId, request.photoIds)

        val alreadyAdded = collabPhotoRepository.findAllByCollabSessionId(session.requiredId)
            .map { it.photoId }
            .toSet()
        if (photos.any { it.requiredId in alreadyAdded }) {
            throw CollabException(CollabErrorCode.PHOTO_ALREADY_ADDED)
        }

        collabPhotoRepository.saveAll(
            photos.map { CollabPhoto(collabSessionId = session.requiredId, photoId = it.requiredId) },
        )

        return photoPage(session.requiredId, page = 0, size = EDIT_RESULT_PAGE_SIZE)
    }

    /**
     * [addPhotos]가 쓴다. 담을 수 있는 사진인지 확인하고 돌려준다.
     *
     * 갤러리 권한만 보고 사진 id를 그대로 믿으면, 자기 갤러리의 세션으로 남의 사진을 끌어와
     * 하객 링크로 서명 URL까지 내보낼 수 있다.
     */
    private fun loadAddablePhotos(galleryId: Long, photoIds: List<Long>): List<Photo> {
        // 빈 목록을 통과시키면 아무 일도 하지 않고 성공한다. 200을 받은 화면은 담긴 줄 안다.
        if (photoIds.isEmpty()) {
            throw CollabException(CollabErrorCode.EMPTY_PHOTO_IDS)
        }
        if (photoIds.size > properties.maxBatchSize) {
            throw CollabException(CollabErrorCode.TOO_MANY_PHOTOS)
        }

        val requested = photoIds.toSet()
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, requested)
        if (photos.size != requested.size) {
            throw CollabException(CollabErrorCode.PHOTO_NOT_IN_GALLERY)
        }
        // 실체가 없는 사진을 담으면 하객 화면에 깨진 이미지가 뜬다. 작가는 그것이 올라오는
        // 중이라는 뜻임을 알지만 하객은 알 도리가 없다.
        if (photos.any { it.status == PhotoStatus.PENDING }) {
            throw CollabException(CollabErrorCode.PHOTO_NOT_UPLOADED)
        }
        return photos
    }

    /**
     * 사진을 뺀다. 그 사진에 달린 댓글과 반응도 함께 사라진다(DB의 `ON DELETE CASCADE`).
     *
     * 세션에 없는 id가 섞여 있어도 막지 않는다. 여러 장을 골라 빼는 화면에서 그중 하나가 이미
     * 빠져 있는 것은 사용자의 실수가 아니라 화면이 조금 낡은 것뿐이다.
     */
    @Transactional
    fun removePhotos(galleryId: Long, userId: Long, request: RemoveCollabPhotosRequest): CollabPhotoPageResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val session = requireSession(galleryId)
        if (request.photoIds.isEmpty()) {
            throw CollabException(CollabErrorCode.EMPTY_PHOTO_IDS)
        }

        collabPhotoRepository.deleteAllByCollabSessionIdAndPhotoIdIn(
            session.requiredId,
            request.photoIds.toSet(),
        )

        return photoPage(session.requiredId, page = 0, size = EDIT_RESULT_PAGE_SIZE)
    }

    /**
     * 하객이 무엇을 어떻게 봤는지. 부부가 결과를 읽는 화면이고 작가도 같은 것을 본다.
     *
     * `myReaction`은 늘 비어 있다 — 부부와 작가는 하객이 아니라 반응을 남기지 않는다.
     */
    @Transactional(readOnly = true)
    fun listPhotos(galleryId: Long, userId: Long, page: Int, size: Int): CollabPhotoPageResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        return photoPage(requireSession(galleryId).requiredId, page, size)
    }

    /**
     * [listPhotos]와, 담고 뺀 결과를 곧바로 돌려주는 두 경로가 함께 쓴다.
     *
     * `@Transactional`을 붙이지 않는다. 같은 클래스 안에서 부르면 프록시를 지나지 않아 선언이
     * 조용히 무시되고, 호출부의 트랜잭션을 그대로 물려받는 편이 실제 동작과도 맞다.
     */
    private fun photoPage(sessionId: Long, page: Int, size: Int): CollabPhotoPageResponse {
        val found = collabPhotoRepository.findAllByCollabSessionIdOrderByIdAsc(
            sessionId,
            CollabPaging.of(page, size, properties.maxBatchSize),
        )

        return CollabPhotoPageResponse(
            photos = collabPhotoViewAssembler.toResponses(found.content, guestId = null),
            page = found.number,
            size = found.size,
            totalCount = found.totalElements,
            hasNext = found.hasNext(),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 부부나 작가가 댓글 하나를 지운다. 하객이 자기 댓글을 지우는 것과 다른 문이다.
     *
     * 그쪽은 "잘못 썼다"이고 이쪽은 "우리 결혼식 사진에 이런 말이 붙어 있는 게 싫다"라,
     * 남이 쓴 글도 지울 수 있어야 한다. 마감 뒤에도 되는 것도 그래서다([requireViewer]).
     */
    @Transactional
    fun deleteComment(galleryId: Long, commentId: Long, userId: Long) {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val session = requireSession(galleryId)
        val comment = collabPhotoCommentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        // 다른 갤러리의 댓글을 자기 갤러리 권한으로 지우지 못하게 한다.
        collabPhotoRepository.findByIdAndCollabSessionId(comment.collabPhotoId, session.requiredId)
            ?: throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)

        collabPhotoCommentRepository.delete(comment)
    }

    /** 세션을 고치는 모든 경로가 여기를 지난다. 잠그는 것이 세션이 아니라 갤러리인 이유는 위에 적었다. */
    private fun lockGallery(galleryId: Long) {
        galleryRepository.findWithLockById(galleryId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND)
    }

    /** 아직 세션을 열지 않은 갤러리다. 조용히 빈 결과를 주면 화면은 링크가 있는 줄 안다. */
    private fun requireSession(galleryId: Long): CollabSession =
        collabSessionRepository.findByGalleryId(galleryId)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)

    private fun toResponse(session: CollabSession): CollabSessionResponse = CollabSessionResponse.of(
        session = session,
        shareUrl = linkAssembler.assemble(session.shareToken),
        photoCount = collabPhotoRepository.countByCollabSessionId(session.requiredId),
    )
}
