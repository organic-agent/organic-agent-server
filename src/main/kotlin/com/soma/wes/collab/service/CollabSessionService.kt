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
import com.soma.wes.collab.support.CollabLinkAssembler
import com.soma.wes.collab.support.CollabPaging
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
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
 * **갤러리 하나에 세션이 여럿이다.** 그래서 세션 하나를 다루는 모든 경로가 `sessionId`를 받고
 * [requireSession]에서 그 세션이 이 갤러리 것인지 함께 확인한다 — 인가는 갤러리 단위라, 세션을
 * id로만 찾으면 자기 갤러리 권한으로 남의 갤러리 세션을 열 수 있다.
 *
 * 고치는 경로는 갤러리 행을 잠그고 시작한다([lockGallery]). 신랑과 신부가 동시에 담는 일이
 * 실제로 일어나는데, 잠그지 않으면 같은 사진을 동시에 담을 때 중복 확인이 둘 다 통과한다.
 * 세션 행이 아니라 갤러리 행을 잠그는 이유는 세션이 아직 없을 수 있어서다(여는 순간). 같은
 * 갤러리의 다른 세션까지 함께 줄을 서지만, 한 갤러리를 동시에 만지는 사람은 둘뿐이라 그 값이 싸다.
 */
@Service
class CollabSessionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val collabSessionRepository: CollabSessionRepository,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoViewAssembler: CollabPhotoViewAssembler,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
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
     * 새 링크를 연다. 부를 때마다 **새 세션**이고, 이름이 그 둘을 가른다.
     *
     * 멱등하지 않다. 갤러리당 하나였을 때는 "이미 있으면 그대로"가 맞았지만, 여러 개인 지금
     * 같은 이름을 막을 이유가 없다 — 부모님께 두 번 나눠 물어볼 수도 있다. 폐기한 링크를
     * 되살리는 것은 [reissueToken]의 일이고, 그쪽은 받은 의견을 그대로 안고 간다.
     *
     * [OpenCollabSessionRequest.folderId]를 주면 그 폴더의 사진을 **복사**해 채운다. 폴더를
     * 가리키지 않고 행으로 떠 넣기 때문에, 세션을 연 뒤 부부가 폴더를 고치거나 지워도 하객이
     * 보던 사진과 거기 달린 의견은 흔들리지 않는다.
     */
    @Transactional
    fun open(galleryId: Long, userId: Long, request: OpenCollabSessionRequest): CollabSessionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        // 저장보다 확인이 먼저다. 폴더가 잘못됐는데 세션만 만들어두면, 부부에게는 실패로 보이는
        // 요청이 이름 없는 빈 링크를 하나 남긴다.
        val photos = request.folderId?.let { loadFolderPhotos(galleryId, it) }
        val session = collabSessionRepository.save(newSession(galleryId, request.name))

        if (photos != null) {
            collabPhotoRepository.saveAll(
                photos.map { CollabPhoto(collabSessionId = session.requiredId, photoId = it.requiredId) },
            )
        }
        return toResponse(session)
    }

    /**
     * [open]이 쓴다. 이름 규칙 위반을 도메인 예외로 옮긴다.
     *
     * [CollabSession.normalizeName]이 던지는 것은 `IllegalArgumentException`이라 그대로 두면 500이
     * 나간다. 컨트롤러의 `@Valid`가 대부분 먼저 걸러내지만, 그 검증은 컨트롤러를 지날 때만 돈다.
     */
    private fun newSession(galleryId: Long, name: String): CollabSession =
        try {
            CollabSession.of(galleryId, name, tokenGenerator.generate())
        } catch (e: IllegalArgumentException) {
            throw CollabException(CollabErrorCode.INVALID_SESSION_NAME)
        }

    /**
     * [open]이 쓴다. 폴더에 담긴 사진을 그대로 세션에 옮길 수 있는지 확인하고 돌려준다.
     *
     * 담을 수 있는 것만 담고 나머지를 버리지 않는다 — [addPhotos]와 같은 판단이다. 부부가 폴더를
     * 고른 것은 "이 묶음을 물어보겠다"는 뜻이라, 그중 몇 장이 조용히 빠진 링크는 부부가 의도한
     * 질문이 아니다.
     */
    private fun loadFolderPhotos(galleryId: Long, folderId: Long): List<Photo> {
        photoFolderRepository.findByIdAndGalleryId(folderId, galleryId)
            ?: throw CollabException(CollabErrorCode.FOLDER_NOT_IN_GALLERY)

        val photoIds = photoFolderItemRepository.findAllByFolderId(folderId).map { it.photoId }
        if (photoIds.isEmpty()) {
            throw CollabException(CollabErrorCode.EMPTY_FOLDER)
        }
        return loadAddablePhotos(galleryId, photoIds)
    }

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
                shareUrl = linkAssembler.assemble(it.shareToken),
                photoCount = photoCounts[it.requiredId] ?: 0L,
            )
        }
    }

    /** 링크를 복사하는 화면과 결과 화면이 함께 부른다. */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        return toResponse(requireSession(galleryId, sessionId))
    }

    /**
     * 이름만 바꾼다. 링크는 그대로다 — 하객이 들고 있는 주소가 이름 때문에 죽으면 안 된다.
     *
     * 링크가 여러 개인 이상 오타는 반드시 난다. 고칠 방법이 없으면 부부는 세션을 새로 열고,
     * 그러면 이미 받은 의견이 잘못된 이름의 세션에 남는다.
     */
    @Transactional
    fun rename(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: RenameCollabSessionRequest,
    ): CollabSessionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val session = requireSession(galleryId, sessionId)
        try {
            session.rename(request.name)
        } catch (e: IllegalArgumentException) {
            throw CollabException(CollabErrorCode.INVALID_SESSION_NAME)
        }
        return toResponse(session)
    }

    /**
     * 링크를 거둬들인다. 단톡방에 잘못 올라갔을 때 할 수 있는 일이다.
     *
     * 담긴 사진과 받은 의견은 지우지 않는다. 부부가 끊고 싶은 것은 링크이지 하객이 남겨준
     * 말이 아니다. 다시 쓰려면 [reissueToken]으로 새 토큰을 받는다.
     */
    @Transactional
    fun revoke(galleryId: Long, sessionId: Long, userId: Long) {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        requireSession(galleryId, sessionId).revoke(ZonedDateTime.now(clock))
    }

    /**
     * 폐기한 세션에 새 링크를 발급한다. 담긴 사진과 이미 받은 의견은 그대로 안고 간다.
     *
     * [open]으로 새 세션을 여는 것과 다르다. 그쪽은 빈 세션이고, 이쪽은 "같은 질문을 새 주소로
     * 다시 묻는다"이다. 같은 토큰을 되살리지 않는 이유는 [CollabSession.reissueToken]에 적었다.
     *
     * 폐기하지 않은 세션에 불러도 막지 않는다. 토큰이 이미 새어 나갔다고 판단한 부부가 폐기와
     * 재발급을 한 번에 하려는 것이고, 그 결과는 어느 쪽이든 "이전 주소는 죽고 새 주소가 산다"로 같다.
     */
    @Transactional
    fun reissueToken(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val session = requireSession(galleryId, sessionId)
        session.reissueToken(tokenGenerator.generate())

        return toResponse(session)
    }

    /**
     * 하객에게 보여줄 사진을 담는다.
     *
     * 확인이 저장보다 먼저다. 담을 수 있는 것만 담고 나머지를 버리면 화면에는 성공으로 보이고,
     * 어느 사진이 빠졌는지는 아무도 모른다 — 선택 앨범이 같은 판단을 한다.
     */
    @Transactional
    fun addPhotos(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: AddCollabPhotosRequest,
    ): CollabPhotoPageResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val session = requireSession(galleryId, sessionId)
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
     * [addPhotos]와 [loadFolderPhotos]가 쓴다. 담을 수 있는 사진인지 확인하고 돌려준다.
     *
     * 갤러리 권한만 보고 사진 id를 그대로 믿으면, 자기 갤러리의 세션으로 남의 사진을 끌어와
     * 하객 링크로 서명 URL까지 내보낼 수 있다. 폴더에서 온 id도 같은 문을 지난다 — 폴더가
     * 이 갤러리 것임을 확인했어도, 그 안의 사진까지 확인한 것은 아니다.
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
    fun removePhotos(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        request: RemoveCollabPhotosRequest,
    ): CollabPhotoPageResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val session = requireSession(galleryId, sessionId)
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
    fun listPhotos(
        galleryId: Long,
        sessionId: Long,
        userId: Long,
        page: Int,
        size: Int,
    ): CollabPhotoPageResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        return photoPage(requireSession(galleryId, sessionId).requiredId, page, size)
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
    fun deleteComment(galleryId: Long, sessionId: Long, commentId: Long, userId: Long) {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val session = requireSession(galleryId, sessionId)
        val comment = collabPhotoCommentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        // 다른 세션의 댓글을 이 세션 경로로 지우지 못하게 한다. 같은 갤러리의 옆 세션도 남이다 —
        // 부모님께 물은 글을 친구들 링크의 화면에서 지울 수 있으면 안 된다.
        collabPhotoRepository.findByIdAndCollabSessionId(comment.collabPhotoId, session.requiredId)
            ?: throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)

        collabPhotoCommentRepository.delete(comment)
    }

    /** 세션을 고치는 모든 경로가 여기를 지난다. 잠그는 것이 세션이 아니라 갤러리인 이유는 위에 적었다. */
    private fun lockGallery(galleryId: Long) {
        galleryRepository.findWithLockById(galleryId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND)
    }

    /**
     * 세션 하나를 다루는 모든 경로가 여기를 지난다.
     *
     * 갤러리를 함께 보는 것이 핵심이다. 인가는 갤러리 단위라 세션을 id로만 찾으면, 자기 갤러리의
     * 부부 권한으로 남의 갤러리 세션 id를 넣어 링크와 하객 의견을 읽을 수 있다.
     */
    private fun requireSession(galleryId: Long, sessionId: Long): CollabSession =
        collabSessionRepository.findByIdAndGalleryId(sessionId, galleryId)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)

    private fun toResponse(session: CollabSession): CollabSessionResponse = CollabSessionResponse.of(
        session = session,
        shareUrl = linkAssembler.assemble(session.shareToken),
        photoCount = collabPhotoRepository.countByCollabSessionId(session.requiredId),
    )
}
