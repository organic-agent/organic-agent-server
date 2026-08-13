package com.soma.wes.gallery.support

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

    /** 갤러리를 소유한 스튜디오의 작가만. 업로드·임베딩·초대가 여기를 지난다. */
    @Transactional(readOnly = true)
    fun requirePhotographer(galleryId: Long, userId: Long): Gallery {
        val gallery = findGallery(galleryId)
        if (!isPhotographer(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    /**
     * 초대받은 예비 부부만. 지금 고를 수 있는 상태인지까지 본다.
     * 작가는 통과하지 못한다 — 작가가 고객 대신 고르면 안 되는 경로에 쓴다.
     */
    @Transactional(readOnly = true)
    fun requireCouple(galleryId: Long, userId: Long): GalleryMember {
        val gallery = findGallery(galleryId)
        val member = findMember(galleryId, userId)

        requireSelectable(gallery)
        return member
    }

    /**
     * 작가는 무조건, 부부는 고를 수 있는 동안만.
     * 클러스터 조회와 폴더 기능이 여기를 지난다. 작가는 어떻게 묶이는지 확인해야 하고 부부는
     * 그것으로 고르므로 둘 다 필요하지만, 마감이 지난 뒤에도 만질 수 있는 것은 작가뿐이다.
     */
    @Transactional(readOnly = true)
    fun requirePhotographerOrCouple(galleryId: Long, userId: Long): Gallery {
        val gallery = findGallery(galleryId)
        if (isPhotographer(gallery, userId)) {
            return gallery
        }

        findMember(galleryId, userId)
        requireSelectable(gallery)
        return gallery
    }

    /** [requireCouple]과 [requirePhotographerOrCouple]이 부부 쪽 분기에서 쓴다. */
    private fun requireSelectable(gallery: Gallery) {
        // 기한 초과와 아직 안 열림은 사용자가 할 수 있는 일이 달라 따로 알려준다.
        // 기한이 지났다면 작가에게 연장을 요청하면 되고, 아직 안 열렸다면 기다리는 수밖에 없다.
        if (gallery.isDeadlinePassed(ZonedDateTime.now(clock))) {
            throw GalleryException(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }
        if (gallery.status != GalleryStatus.OPEN) {
            throw GalleryException(GalleryErrorCode.GALLERY_NOT_OPEN)
        }
    }

    /**
     * 갤러리를 열람할 수 있는지만. 마감과 무관하다.
     * 갤러리 상세는 마감 뒤에도 보여야 한다 — 마감됐다는 사실 자체를 그 화면에서 알려준다.
     */
    @Transactional(readOnly = true)
    fun requireViewer(galleryId: Long, userId: Long): Gallery {
        val gallery = findGallery(galleryId)
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

    /** 위 `require*` 넷이 모두 쓴다. */
    private fun findGallery(galleryId: Long): Gallery =
        galleryRepository.findById(galleryId)
            .orElseThrow { GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND) }

    /** 부부 분기가 쓴다. 멤버가 아니면 갤러리의 존재 자체를 알려주지 않는다. */
    private fun findMember(galleryId: Long, userId: Long): GalleryMember =
        galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)

    /** 내부에서도 쓰지만 [com.soma.wes.gallery.service.GalleryInviteService]가 부르는 공개 API다. */
    @Transactional(readOnly = true)
    fun isPhotographer(gallery: Gallery, userId: Long): Boolean =
        studioRepository.findByUserId(userId)?.id == gallery.studioId
}
