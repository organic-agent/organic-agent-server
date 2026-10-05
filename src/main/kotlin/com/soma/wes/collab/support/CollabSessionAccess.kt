package com.soma.wes.collab.support

import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.domain.CollabParticipant
import com.soma.wes.collab.dto.CollabAccessDto
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabParticipantRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.requireById
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository

/**
 * 협업 링크를 들고 온 요청이 무엇을 할 수 있는지.
 *
 * [com.soma.wes.gallery.support.GalleryAccessPolicy]와 나란한 자리지만 판단 근거가 다르다.
 * 저쪽은 `userId`로 역할(작가·부부)을 묻고, 이쪽에는 계정 자체가 없어 **토큰이 곧 자격**이다.
 * 한 파일에 섞으면 "메서드 이름이 역할이다"라는 그쪽 규칙이 첫 줄부터 깨진다.
 *
 * 문이 넷이다:
 * - [requireReadable] — 보는 것. 갤러리 마감 뒤에도 열리지만 링크 자체의 만료는 지킨다.
 * - [requireLikable] — 좋아요. 갤러리가 보관되기 전까지 열린다.
 * - [requireWritable] — 댓글. 부부가 고르는 동안에만 열린다.
 * - [requireGuest] — 남기는 사람이 누구인지. 하객 토큰을 확인한다. 보기만 할 때는 [findGuest]로
 *   묻는다 — 같은 토큰을 같은 방식으로 풀되, 없다고 막지는 않는다.
 */
@Component
class CollabSessionAccess(
    private val collabSessionRepository: CollabSessionRepository,
    private val participantRepository: CollabParticipantRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
    private val workspaceRepository: com.soma.wes.workspace.repository.WorkspaceRepository,
    private val photoRepository: PhotoRepository,
) {

    /**
     * 링크로 볼 수 있는지. 사진 목록과 댓글 조회가 여기를 지난다.
     *
     * 마감이나 갤러리 상태를 보지 않는다. 부부가 고르기를 끝냈어도 하객이 자기가 남긴 말과
     * 그 사진을 다시 열어보는 것까지 막을 이유가 없다 — 보는 것은 고르는 것이 아니다.
     */
    fun requireReadable(collabToken: String): CollabAccessDto {
        val session = collabSessionRepository.findByCollabToken(collabToken)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
        return requireReadable(session)
    }

    private fun requireReadable(session: CollabSession): CollabAccessDto {
        if (session.isRevoked) {
            throw CollabException(CollabErrorCode.SESSION_REVOKED)
        }
        if (session.isExpiredAt(ZonedDateTime.now(clock))) {
            throw CollabException(CollabErrorCode.SESSION_EXPIRED)
        }

        // 세션이 있는데 갤러리가 없으면 데이터가 깨진 것이다.
        val gallery = galleryRepository.requireById(session.galleryId)
        // 부부가 세션을 여는 시점에 갤러리는 이미 열려 있다. 나중에 DRAFT로 되돌리는 경로가
        // 생기는 날, 링크 하나로 그 갤러리가 다시 공개되지 않도록 여기서 함께 막는다.
        if (!gallery.isVisibleToMember) {
            throw CollabException(CollabErrorCode.SESSION_NOT_READY)
        }
        return CollabAccessDto(session, gallery)
    }

    /**
     * 링크로 댓글을 남길 수 있는지. 댓글 쓰기·지우기가 여기를 지난다.
     *
     * 부부가 고를 수 있는 동안에만 열린다 —
     * [com.soma.wes.gallery.support.GalleryAccessPolicy.requireSelectionEditor]과 같은 기준이다.
     * 마감된 갤러리에 하객 의견이 계속 쌓이면, 그 의견은 아무도 읽지 않을 곳에 쌓인다.
     */
    @Transactional
    fun requireWritable(collabToken: String, photoId: Long? = null): CollabAccessDto {
        val access = lockForFeedback(collabToken, photoId)
        if (!isWritable(access)) {
            throw CollabException(CollabErrorCode.FEEDBACK_CLOSED)
        }
        return access
    }

    /**
     * 링크로 좋아요를 누르거나 거둘 수 있는지.
     *
     * 댓글과 달리 선택 마감을 보지 않는다. 좋아요는 부부에게 고를 일을 남기는 의견이 아니라 사진을
     * 함께 보는 반응이라, 고르기가 끝난 뒤 링크를 받은 하객도 누를 수 있어야 한다.
     * 갤러리가 보관되거나 이용 기간이 끝나면 닫는다 — 그때는 부부도 갤러리를 고칠 수 없다.
     */
    @Transactional
    fun requireLikable(collabToken: String, photoId: Long): CollabAccessDto {
        val access = lockForFeedback(collabToken, photoId)
        if (!isLikable(access)) {
            throw CollabException(CollabErrorCode.FEEDBACK_CLOSED)
        }
        return access
    }

    private fun lockForFeedback(collabToken: String, photoId: Long?): CollabAccessDto {
        if (photoId != null) {
            val galleryId = collabSessionRepository.findGalleryIdByCollabToken(collabToken)
                ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
            // 사진 삭제와 반응 저장이 사진 → 세션 순서를 공유한다. 세션은 잠금 뒤 새로 읽는다.
            val photo = photoRepository.findWithLockByIdAndGalleryId(photoId, galleryId)
                ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
            if (photo.status == PhotoStatus.PENDING) throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }
        val session = collabSessionRepository.findWithLockByCollabToken(collabToken)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
        return requireReadable(session)
    }

    /**
     * [requireWritable]과 같은 판단을 예외 없이 돌려준다. 첫 화면이 댓글창을 띄울지 정할 때 쓴다.
     *
     * 화면이 이 조건을 자기 쪽에서 다시 계산하면(마감 시각을 받아 비교하는 식으로) 서버가 막는
     * 기준과 어긋나는 날이 오고, 그때 하객은 열려 있는 입력창에 쓴 글을 403으로 돌려받는다.
     *
     * 개인 갤러리의 `selectionDeadline`은 목표일일 뿐이라 보지 않는다 — 부부의 고르기와 같은 기준이다.
     */
    fun isWritable(access: CollabAccessDto): Boolean =
        isLikable(access) && (isPersonal(access) || !access.gallery.isDeadlinePassed(ZonedDateTime.now(clock)))

    /** [requireLikable]과 같은 판단을 예외 없이 돌려준다. 첫 화면이 좋아요 버튼을 띄울지 정할 때 쓴다. */
    fun isLikable(access: CollabAccessDto): Boolean {
        val gallery = access.gallery
        val now = ZonedDateTime.now(clock)
        return gallery.status == GalleryStatus.OPEN &&
            gallery.stage != com.soma.wes.gallery.domain.GalleryStage.ARCHIVED &&
            gallery.planExpiresAt?.let { it.isAfter(now) } != false
    }

    /**
     * 글을 남기는 사람이 누구인지. 갤러리 참여자(부부)는 계정으로, 그 밖의 사람은 닉네임을 적고
     * 받아간 토큰으로 확인한다.
     *
     * 참여자가 아닌 계정으로 로그인한 하객도 게스트로 본다. 계정을 이유로 막아도 로그아웃하면 같은
     * 사람이 게스트로 남길 수 있어 지켜지는 것이 없다.
     *
     * 토큰이 없는 것과 틀린 것을 구분하지 않는다. 화면이 할 일은 어느 쪽이든 같다 —
     * 닉네임을 다시 받아 새로 입장시키면 된다.
     */
    fun requireParticipant(access: CollabAccessDto, userId: Long?, guestToken: String?): CollabParticipant {
        if (userId != null && canUserReact(access, userId)) {
            return participantRepository.findByCollabSessionIdAndUserId(access.sessionId, userId)
                ?: participantRepository.save(
                    CollabParticipant.user(access.sessionId, userId, userRepository.requireById(userId).nickname),
                )
        }
        return findGuest(access, guestToken) ?: throw CollabException(CollabErrorCode.GUEST_NOT_IDENTIFIED)
    }

    /**
     * 보고 있는 사람이 누구인지. 조회 경로가 쓴다. [requireParticipant]와 같은 순서로 찾는다.
     *
     * 토큰이 없거나 이 세션의 것이 아니면 그냥 익명으로 본다 — 보는 것을 막을 이유가 없고,
     * 화면에서는 "내가 누른 반응"과 "내가 쓴 댓글" 표시만 비어 보인다.
     */
    fun findParticipant(access: CollabAccessDto, userId: Long?, guestToken: String?): CollabParticipant? {
        if (userId != null && canUserReact(access, userId)) {
            return participantRepository.findByCollabSessionIdAndUserId(access.sessionId, userId)
        }
        return findGuest(access, guestToken)
    }

    fun requireGuest(access: CollabAccessDto, guestToken: String?): CollabParticipant =
        findGuest(access, guestToken) ?: throw CollabException(CollabErrorCode.GUEST_NOT_IDENTIFIED)

    fun findGuest(access: CollabAccessDto, guestToken: String?): CollabParticipant? {
        if (guestToken.isNullOrBlank()) {
            return null
        }

        return participantRepository.findByGuestTokenAndCollabSessionId(guestToken, access.sessionId)
    }

    private fun canUserReact(access: CollabAccessDto, userId: Long): Boolean {
        if (galleryMemberRepository.findByGalleryIdAndUserId(access.gallery.requiredId, userId) != null) {
            return true
        }
        if (!isPersonal(access)) return false
        return workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(
            access.gallery.workspaceId,
            userId,
            WorkspaceRole.entries,
        )
    }

    private fun isPersonal(access: CollabAccessDto): Boolean =
        workspaceRepository.findById(access.gallery.workspaceId).orElse(null)?.type ==
            com.soma.wes.workspace.domain.WorkspaceType.PERSONAL
}
