package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.dto.response.GalleryInviteAcceptResponse
import com.soma.wes.gallery.dto.response.GalleryInviteResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireByIdAndGalleryId
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class GalleryInviteService(
    private val galleryRepository: GalleryRepository,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val tokenGenerator: SecureTokenGenerator,
    private val urlResolver: GalleryInviteUrlResolver,
    private val clock: Clock,
) {

    companion object {
        val VALIDITY: Duration = Duration.ofDays(7)
    }

    /**
     * 링크를 발급한다. 재발급이다 — 갤러리에 살아 있던 링크는 이 자리에서 폐기된다.
     */
    @Transactional
    fun issue(galleryId: Long, userId: Long): GalleryInviteResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)
        galleryRepository.requireWithLockById(galleryId)

        val now = ZonedDateTime.now(clock)
        galleryInviteRepository.findByGalleryIdAndRevokedAtIsNull(galleryId)?.let { previous ->
            previous.revoke(now)
            galleryInviteRepository.flush()
        }

        val invite = galleryInviteRepository.save(
            GalleryInvite(
                galleryId = galleryId,
                token = tokenGenerator.generate(),
                expiresAt = now.plus(VALIDITY),
            ),
        )
        return toResponse(invite, now)
    }

    /**
     * 갤러리의 현재 링크. 아직 발급한 적이 없으면 404다.
     */
    @Transactional(readOnly = true)
    fun getCurrent(galleryId: Long, userId: Long): GalleryInviteResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val invite = galleryInviteRepository.findByGalleryIdAndRevokedAtIsNull(galleryId)
            ?: throw GalleryException(GalleryErrorCode.INVITE_NOT_FOUND)
        return toResponse(invite, ZonedDateTime.now(clock))
    }

    private fun toResponse(invite: GalleryInvite, at: ZonedDateTime): GalleryInviteResponse =
        GalleryInviteResponse.of(
            invite = invite,
            inviteUrl = urlResolver.resolve(invite.token),
            at = at,
        )

    /**
     * 링크를 거둬들인다. 링크가 엉뚱한 곳에 퍼졌을 때 쓰며, 이미 들어온 멤버는 그대로 남는다.
     */
    @Transactional
    fun revoke(galleryId: Long, inviteId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val invite = galleryInviteRepository.requireByIdAndGalleryId(inviteId, galleryId)

        invite.revoke(ZonedDateTime.now(clock))
    }

    /**
     * 링크를 눌러 갤러리에 들어온다. 같은 사람이 여러 번 눌러도 멤버는 하나이며 매번 성공한다.
     */
    @Transactional
    fun accept(token: String, userId: Long): GalleryInviteAcceptResponse {
        val invite = galleryInviteRepository.findByToken(token)
            ?: throw GalleryException(GalleryErrorCode.INVITE_NOT_FOUND)

        // 폐기와 만료를 나눠 알려준다. 만료라면 작가에게 재발급을 요청하면 되는 일이다.
        if (invite.isRevoked) {
            throw GalleryException(GalleryErrorCode.INVITE_REVOKED)
        }
        if (invite.isExpiredAt(ZonedDateTime.now(clock))) {
            throw GalleryException(GalleryErrorCode.INVITE_EXPIRED)
        }

        galleryAccessPolicy.requireNotPhotographer(invite.galleryId, userId)
        galleryRepository.requireWithLockById(invite.galleryId)

        galleryMemberRepository.findByGalleryIdAndUserId(invite.galleryId, userId)
            ?.let { return GalleryInviteAcceptResponse.from(it) }

        if (galleryMemberRepository.countByGalleryId(invite.galleryId) >= GalleryMember.MAX_PER_GALLERY) {
            throw GalleryException(GalleryErrorCode.GALLERY_MEMBER_LIMIT_EXCEEDED)
        }

        val member = galleryMemberRepository.save(
            GalleryMember(galleryId = invite.galleryId, userId = userId),
        )
        return GalleryInviteAcceptResponse.from(member)
    }

}
