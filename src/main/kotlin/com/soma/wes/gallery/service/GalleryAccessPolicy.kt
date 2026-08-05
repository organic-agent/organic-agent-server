package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.repository.StudioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime


@Service
@Transactional(readOnly = true)
class GalleryAccessPolicy(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val clock: Clock,
) {

    fun requireManager(galleryId: Long, userId: Long): Gallery {
        val gallery = findGallery(galleryId)
        if (!isManager(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    fun requireViewer(galleryId: Long, userId: Long): Gallery {
        val gallery = findGallery(galleryId)
        if (isManager(gallery, userId)) {
            return gallery
        }

        findMember(galleryId, userId)
        if (!gallery.isVisibleToMember) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    fun requireSelector(galleryId: Long, userId: Long): GalleryMember {
        val gallery = findGallery(galleryId)
        val member = findMember(galleryId, userId)

        // 기한 초과와 아직 안 열림은 사용자가 할 수 있는 일이 달라 따로 알려준다.
        val now = ZonedDateTime.now(clock)
        if (gallery.isDeadlinePassed(now)) {
            throw GalleryException(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }
        if (gallery.status != GalleryStatus.OPEN) {
            throw GalleryException(GalleryErrorCode.GALLERY_NOT_OPEN)
        }
        return member
    }

    fun isManager(gallery: Gallery, userId: Long): Boolean =
        studioRepository.findByUserId(userId)?.id == gallery.studioId

    private fun findGallery(galleryId: Long): Gallery =
        galleryRepository.findById(galleryId)
            .orElseThrow { GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND) }

    private fun findMember(galleryId: Long, userId: Long): GalleryMember =
        galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
}
