package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryInviteTokenGenerator
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 초대 링크의 발급·폐기와 수락을 다룬다.
 *
 * 발급·폐기는 담당 작가만 할 수 있고, 수락은 링크를 가진 누구나 할 수 있다.
 * 그래서 수락 경로는 인증된 사용자여야 한다는 것 외에는 갤러리 권한을 요구하지 않는다 —
 * 권한을 만들어주는 것이 이 동작이기 때문이다.
 */
@Service
class GalleryInviteService(
    private val galleryRepository: GalleryRepository,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val userRepository: UserRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val tokenGenerator: GalleryInviteTokenGenerator,
    private val clock: Clock,
) {

    companion object {
        val VALIDITY: Duration = Duration.ofDays(7)
    }

    @Transactional
    fun issue(galleryId: Long, userId: Long): GalleryInvite {
        galleryAccessPolicy.requireManager(galleryId, userId)

        return galleryInviteRepository.save(
            GalleryInvite(
                galleryId = galleryId,
                token = tokenGenerator.generate(),
                expiresAt = ZonedDateTime.now(clock).plus(VALIDITY),
            ),
        )
    }

    /**
     * 링크를 거둬들인다. 링크가 엉뚱한 곳에 퍼졌을 때 쓰며, 이미 들어온 멤버는 그대로 남는다.
     * 내보내는 것은 멤버 삭제라는 별개의 동작이다.
     */
    @Transactional
    fun revoke(galleryId: Long, inviteId: Long, userId: Long) {
        galleryAccessPolicy.requireManager(galleryId, userId)

        val invite = galleryInviteRepository.findById(inviteId)
            .orElseThrow { GalleryException(GalleryErrorCode.INVITE_NOT_FOUND) }
        // 다른 갤러리의 초대를 자기 갤러리 권한으로 폐기하지 못하게 한다.
        if (invite.galleryId != galleryId) {
            throw GalleryException(GalleryErrorCode.INVITE_NOT_FOUND)
        }

        invite.revoke(ZonedDateTime.now(clock))
    }

    /**
     * 링크를 눌러 갤러리에 들어온다. 같은 사람이 여러 번 눌러도 멤버는 하나이며 매번 성공한다.
     *
     * 링크를 전달받아 처음 여는 사람과 이미 들어온 사람을 구분해 응답할 이유가 없다.
     * 두 번째 요청에 에러를 돌려주면 "링크가 잘못됐나" 싶게 만들 뿐이다.
     */
    @Transactional
    fun accept(token: String, userId: Long): GalleryMember {
        val invite = galleryInviteRepository.findByToken(token)
            ?: throw GalleryException(GalleryErrorCode.INVITE_NOT_FOUND)

        // 폐기와 만료를 나눠 알려준다. 만료라면 작가에게 재발급을 요청하면 되는 일이다.
        if (invite.isRevoked) {
            throw GalleryException(GalleryErrorCode.INVITE_REVOKED)
        }
        if (invite.isExpiredAt(ZonedDateTime.now(clock))) {
            throw GalleryException(GalleryErrorCode.INVITE_EXPIRED)
        }

        val gallery = galleryRepository.findById(invite.galleryId)
            .orElseThrow { GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND) }
        if (galleryAccessPolicy.isManager(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.MANAGER_CANNOT_ACCEPT_INVITE)
        }

        // TODO(#3과 같은 부류): 같은 사용자의 동시 요청 둘이 모두 이 검사를 통과하면
        //  UK(gallery_id, user_id)에 걸려 한쪽이 실패한다. 중복 멤버가 생기지는 않는다.
        galleryMemberRepository.findByGalleryIdAndUserId(invite.galleryId, userId)
            ?.let { return it }

        confirmAsClientIfNotOnboarded(userId)

        return galleryMemberRepository.save(
            GalleryMember(galleryId = invite.galleryId, userId = userId),
        )
    }

    /**
     * 예비 부부의 온보딩. 초대 링크로만 가입할 수 있으므로 수락이 곧 종류 확정이다.
     *
     * **아직 정해지지 않았을 때만** 정한다. 작가가 남의 갤러리에 초대받는 것은 정상 시나리오인데
     * ([MANAGER_CANNOT_ACCEPT_INVITE][GalleryErrorCode.MANAGER_CANNOT_ACCEPT_INVITE]는 자기
     * 갤러리만 막는다), 무조건 덮어쓰면 이미 PHOTOGRAPHER인 사용자가
     * `USER_TYPE_ALREADY_SELECTED`에 걸려 초대 수락 자체가 실패한다.
     */
    private fun confirmAsClientIfNotOnboarded(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { UserException(UserErrorCode.USER_NOT_FOUND) }

        if (!user.isOnboarded) {
            user.selectType(UserType.CLIENT)
        }
    }
}
