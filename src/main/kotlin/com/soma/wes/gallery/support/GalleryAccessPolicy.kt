package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireById
import com.soma.wes.studio.repository.StudioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 갤러리에서 누가 무엇을 할 수 있는지.
 */
@Service
class GalleryAccessPolicy(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val clock: Clock,
) {

    /** 갤러리 상태 전이·계약 장수·업로드 URL 발급·임베딩 실행·초대 발급/폐기·제출 철회. */
    @Transactional(readOnly = true)
    fun requirePhotographer(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (!isPhotographer(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    /**
     * 초대 수락.
     */
    @Transactional(readOnly = true)
    fun requireNotPhotographer(galleryId: Long, userId: Long) {
        val gallery = galleryRepository.requireById(galleryId)
        if (isPhotographer(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.MANAGER_CANNOT_ACCEPT_INVITE)
        }
    }

    /**
     * 선택 앨범의 담기·빼기·제출, 협업 세션 개설과 큐레이션.
     */
    @Transactional(readOnly = true)
    fun requireCouple(galleryId: Long, userId: Long): GalleryMember {
        val gallery = galleryRepository.requireById(galleryId)
        val member = findMember(galleryId, userId)

        requireSelectable(gallery)
        return member
    }

    /**
     * 갤러리 상세, 사진 목록, 선택 앨범 조회, 협업 결과 조회.
     */
    @Transactional(readOnly = true)
    fun requireViewer(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (isPhotographer(gallery, userId)) {
            return gallery
        }

        findMember(galleryId, userId)
        // DRAFT는 아직 초대받은 사람에게 보이지 않는다.
        if (!gallery.isVisibleToMember) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    /**
     * 클러스터 조회, 폴더 전반, 사진 상세, 별점 주기/지우기.
     */
    @Transactional(readOnly = true)
    fun requirePhotographerOrCouple(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (isPhotographer(gallery, userId)) {
            return gallery
        }

        findMember(galleryId, userId)
        requireSelectable(gallery)
        return gallery
    }

    private fun isPhotographer(gallery: Gallery, userId: Long): Boolean =
        studioRepository.findByUserId(userId)?.id == gallery.studioId

    private fun findMember(galleryId: Long, userId: Long): GalleryMember =
        galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)

    private fun requireSelectable(gallery: Gallery) {
        if (gallery.isDeadlinePassed(ZonedDateTime.now(clock))) {
            throw GalleryException(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }
        if (gallery.status != GalleryStatus.OPEN) {
            throw GalleryException(GalleryErrorCode.GALLERY_NOT_OPEN)
        }
    }
}
