package com.soma.wes.collab.support

import com.soma.wes.collab.domain.CollabGuest
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabGuestRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 협업 링크를 들고 온 요청이 무엇을 할 수 있는지.
 *
 * [com.soma.wes.gallery.support.GalleryAccessPolicy]와 나란한 자리지만 판단 근거가 다르다.
 * 저쪽은 `userId`로 역할(작가·부부)을 묻고, 이쪽에는 계정 자체가 없어 **토큰이 곧 자격**이다.
 * 한 파일에 섞으면 "메서드 이름이 역할이다"라는 그쪽 규칙이 첫 줄부터 깨진다.
 *
 * 문이 셋이다:
 * - [requireReadable] — 보는 것. 마감된 뒤에도 열려 있다.
 * - [requireWritable] — 남기는 것. 부부가 고르는 동안에만 열린다.
 * - [requireGuest] — 남기는 사람이 누구인지. 하객 토큰을 확인한다.
 */
@Component
class CollabSessionAccess(
    private val collabSessionRepository: CollabSessionRepository,
    private val collabGuestRepository: CollabGuestRepository,
    private val galleryRepository: GalleryRepository,
    private val clock: Clock,
) {

    /**
     * 링크로 볼 수 있는지. 사진 목록과 댓글 조회가 여기를 지난다.
     *
     * 마감이나 갤러리 상태를 보지 않는다. 부부가 고르기를 끝냈어도 하객이 자기가 남긴 말과
     * 그 사진을 다시 열어보는 것까지 막을 이유가 없다 — 보는 것은 고르는 것이 아니다.
     */
    fun requireReadable(shareToken: String): CollabAccess {
        val session = collabSessionRepository.findByShareToken(shareToken)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
        if (session.isRevoked) {
            throw CollabException(CollabErrorCode.SESSION_REVOKED)
        }

        val gallery = findGallery(session.galleryId)
        // 부부가 세션을 여는 시점에 갤러리는 이미 열려 있다. 나중에 DRAFT로 되돌리는 경로가
        // 생기는 날, 링크 하나로 그 갤러리가 다시 공개되지 않도록 여기서 함께 막는다.
        if (!gallery.isVisibleToMember) {
            throw CollabException(CollabErrorCode.SESSION_NOT_READY)
        }
        return CollabAccess(session, gallery)
    }

    /**
     * 링크로 의견을 남길 수 있는지. 댓글과 반응이 여기를 지난다.
     *
     * 부부가 고를 수 있는 동안에만 열린다 —
     * [com.soma.wes.gallery.support.GalleryAccessPolicy.requireCouple]과 같은 기준이다.
     * 마감된 갤러리에 하객 의견이 계속 쌓이면, 그 의견은 아무도 읽지 않을 곳에 쌓인다.
     */
    fun requireWritable(shareToken: String): CollabAccess {
        val access = requireReadable(shareToken)

        if (!isWritable(access)) {
            throw CollabException(CollabErrorCode.FEEDBACK_CLOSED)
        }
        return access
    }

    /**
     * [requireWritable]과 같은 판단을 예외 없이 돌려준다. 첫 화면이 댓글창을 띄울지 정할 때 쓴다.
     *
     * 화면이 이 조건을 자기 쪽에서 다시 계산하면(마감 시각을 받아 비교하는 식으로) 서버가 막는
     * 기준과 어긋나는 날이 오고, 그때 하객은 열려 있는 입력창에 쓴 글을 403으로 돌려받는다.
     */
    fun isWritable(access: CollabAccess): Boolean {
        val gallery = access.gallery
        return gallery.status == GalleryStatus.OPEN && !gallery.isDeadlinePassed(ZonedDateTime.now(clock))
    }

    /**
     * 글을 남기는 사람이 누구인지. 닉네임을 적고 받아간 토큰으로 확인한다.
     *
     * 토큰이 없는 것과 틀린 것을 구분하지 않는다. 화면이 할 일은 어느 쪽이든 같다 —
     * 닉네임을 다시 받아 새로 입장시키면 된다.
     */
    fun requireGuest(access: CollabAccess, guestToken: String?): CollabGuest {
        if (guestToken.isNullOrBlank()) {
            throw CollabException(CollabErrorCode.GUEST_NOT_IDENTIFIED)
        }

        return collabGuestRepository.findByGuestTokenAndCollabSessionId(guestToken, access.sessionId)
            ?: throw CollabException(CollabErrorCode.GUEST_NOT_IDENTIFIED)
    }

    /** [requireReadable]이 쓴다. 세션이 있는데 갤러리가 없으면 데이터가 깨진 것이다. */
    private fun findGallery(galleryId: Long): Gallery =
        galleryRepository.findById(galleryId)
            .orElseThrow { GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND) }
}
