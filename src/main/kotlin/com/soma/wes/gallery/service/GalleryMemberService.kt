package com.soma.wes.gallery.service

import com.soma.wes.gallery.dto.response.GalleryMemberResponse
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireByIdAndGalleryId
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class GalleryMemberService(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val userRepository: UserRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
) {

    /**
     * 갤러리에 들어와 있는 사람들. 작가와 부부 모두 본다.
     */
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<GalleryMemberResponse> {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val members = galleryMemberRepository.findAllByGalleryId(galleryId)
        val usersById = userRepository.findAllById(members.map { it.userId })
            .associateBy { it.requiredId }

        return members.mapNotNull { member ->
            usersById[member.userId]?.let { GalleryMemberResponse.of(member, it) }
        }
    }

    /**
     * 멤버를 내보낸다. 담당 작가만 할 수 있다.
     */
    @Transactional
    fun remove(galleryId: Long, memberId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)
        // 정원을 바꾸는 경로는 전부 갤러리 행을 잠그고 시작한다.
        galleryRepository.requireWithLockById(galleryId)

        galleryMemberRepository.delete(
            galleryMemberRepository.requireByIdAndGalleryId(memberId, galleryId),
        )
    }
}
