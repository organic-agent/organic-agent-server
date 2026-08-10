package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabGuest
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.response.CollabCommentPageResponse
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabGuestRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.support.CollabAccess
import com.soma.wes.collab.support.CollabPaging
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.photo.config.StorageProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 하객이 링크로 들어와 보는 것들. 로그인하지 않은 사람이 부르는 유일한 서비스다.
 *
 * 인가가 `userId`가 아니라 **토큰 두 개**로 끝난다 — 경로의 공유 토큰이 "이 갤러리를 봐도
 * 되는가"를, 헤더의 하객 토큰이 "당신이 누구인가"를 답한다. 뒤엣것은 볼 때는 없어도 되고
 * ([CollabSessionAccess.requireGuest]를 지나지 않는다) 남길 때만 필요하다.
 *
 * 조회 경로에서 하객 토큰은 **있으면 쓰고 없으면 넘어간다**. 자기가 누른 반응과 자기가 쓴
 * 댓글을 화면이 표시하려면 필요하지만, 그것 때문에 닉네임부터 받게 하면 링크를 연 사람이
 * 사진을 보기도 전에 입력창을 만난다.
 */
@Service
class CollabGuestService(
    private val collabSessionAccess: CollabSessionAccess,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabGuestRepository: CollabGuestRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoViewAssembler: CollabPhotoViewAssembler,
    private val tokenGenerator: SecureTokenGenerator,
    private val properties: StorageProperties,
) {

    /**
     * 링크를 열었을 때 처음 보는 것. 하객 토큰 없이 부른다.
     *
     * 여기서 `writable`이 내려가는 덕분에 화면은 "지금 의견을 받는 중인지"를 첫 화면에서 알고,
     * 댓글창을 띄울지 감출지 정한다.
     */
    @Transactional(readOnly = true)
    fun open(shareToken: String): CollabLandingResponse {
        val access = collabSessionAccess.requireReadable(shareToken)

        return CollabLandingResponse(
            galleryTitle = access.gallery.title,
            photoCount = collabPhotoRepository.countByCollabSessionId(access.sessionId),
            writable = collabSessionAccess.isWritable(access),
        )
    }

    /**
     * 닉네임을 적고 들어온다. 서버가 하객 토큰을 발급한다.
     *
     * 이미 들어온 사람이 다시 부르면 새 하객이 하나 더 생긴다 — 토큰을 들고 있다면 화면이 이
     * API를 부를 이유가 없고, 부른다는 것은 토큰을 잃었거나 다른 기기라는 뜻이라 새 사람으로
     * 보는 편이 맞다. 같은 닉네임을 막지 않는 이유도 같다.
     *
     * 의견을 받지 않는 상태면 입장 자체가 막힌다([CollabSessionAccess.requireWritable]).
     * 들여보내 봐야 할 수 있는 일이 없는데, 닉네임만 받아두면 하객은 그것을 댓글을 쓸 수 있다는
     * 뜻으로 읽는다.
     */
    @Transactional
    fun enter(shareToken: String, request: EnterCollabRequest): CollabGuestResponse {
        val access = collabSessionAccess.requireWritable(shareToken)

        val guest = collabGuestRepository.save(
            CollabGuest(
                collabSessionId = access.sessionId,
                guestToken = tokenGenerator.generate(),
                nickname = CollabGuest.requireValidNickname(request.nickname),
            ),
        )
        return CollabGuestResponse.from(guest)
    }

    /**
     * 부부가 보여주는 사진들. 갤러리 전체가 아니라 세션에 담긴 것만이다.
     *
     * 사진마다 반응 수와 댓글 수가 함께 온다 — 하객도 "다들 이 사진을 좋아하는구나"를 보면서
     * 고르게 되고, 그 수를 숨기면 부부만 아는 값이 되어 화면에 쓸 곳이 없다.
     */
    @Transactional(readOnly = true)
    fun listPhotos(shareToken: String, guestToken: String?, page: Int, size: Int): CollabPhotoPageResponse {
        val access = collabSessionAccess.requireReadable(shareToken)

        val found = collabPhotoRepository.findAllByCollabSessionIdOrderByIdAsc(
            access.sessionId,
            CollabPaging.of(page, size, properties.maxBatchSize),
        )

        return CollabPhotoPageResponse(
            photos = collabPhotoViewAssembler.toResponses(found.content, guestId = findGuestId(access, guestToken)),
            page = found.number,
            size = found.size,
            totalCount = found.totalElements,
            hasNext = found.hasNext(),
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 사진 한 장에 달린 댓글. 최근 것이 위로 온다.
     *
     * 닉네임은 댓글 행이 아니라 하객 행에서 읽는다. 복사해두면 이름을 고쳤을 때 과거 댓글이
     * 남이 쓴 것처럼 보인다.
     */
    @Transactional(readOnly = true)
    fun listComments(
        shareToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        page: Int,
        size: Int,
    ): CollabCommentPageResponse {
        val access = collabSessionAccess.requireReadable(shareToken)
        requireCollabPhoto(access, collabPhotoId)

        val found = collabPhotoCommentRepository.findAllByCollabPhotoIdOrderByIdDesc(
            collabPhotoId,
            CollabPaging.of(page, size, properties.maxBatchSize),
        )
        val nicknames = collabGuestRepository.findAllByIdIn(found.content.map { it.collabGuestId })
            .associate { it.requiredId to it.nickname }
        val myGuestId = findGuestId(access, guestToken)

        return CollabCommentPageResponse(
            comments = found.content.map {
                CollabCommentResponse(
                    commentId = it.requiredId,
                    nickname = nicknames[it.collabGuestId] ?: UNKNOWN_NICKNAME,
                    content = it.content,
                    createdAt = it.createdAt,
                    mine = myGuestId != null && it.isWrittenBy(myGuestId),
                )
            },
            page = found.number,
            size = found.size,
            totalCount = found.totalElements,
            hasNext = found.hasNext(),
        )
    }

    /** 두 조회 경로가 쓴다. 이 세션에 담긴 사진이 아니면 남의 세션을 들여다보는 요청이다. */
    private fun requireCollabPhoto(access: CollabAccess, collabPhotoId: Long) {
        collabPhotoRepository.findByIdAndCollabSessionId(collabPhotoId, access.sessionId)
            ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
    }

    /**
     * 조회에서만 쓴다. 토큰이 없거나 이 세션의 것이 아니면 그냥 익명으로 본다 —
     * 보는 것을 막을 이유가 없고, 화면에서 "내가 누른 반응"만 비어 보인다.
     */
    private fun findGuestId(access: CollabAccess, guestToken: String?): Long? {
        if (guestToken.isNullOrBlank()) {
            return null
        }

        return collabGuestRepository.findByGuestTokenAndCollabSessionId(guestToken, access.sessionId)?.requiredId
    }

    companion object {
        /** 하객 행이 사라진 댓글. FK가 함께 지우므로 정상 경로에서는 나오지 않는다. */
        private const val UNKNOWN_NICKNAME = "알 수 없음"
    }
}
