package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.dto.request.ChangeMaxSelectablePhotoCountRequest
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.ReopenGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.studio.support.StudioWriteAdmission
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime


@Service
class GalleryService(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val studioWriteAdmission: StudioWriteAdmission,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val clock: Clock,
) {

    /**
     * 새 갤러리. hard delete가 진행 중인 스튜디오라면 저장 전에 거절된다(`STUDIO_409_7`).
     *
     * 삭제 준비가 스냅샷을 뜬 뒤 S3를 먼저 지우고 DB를 나중에 지우므로, 그 사이에 갤러리가
     * 끼어들면 `DELETION_TARGET_CHANGED`로 삭제가 멈추는데 사진 객체는 이미 사라진 뒤다.
     * [com.soma.wes.gallery.support.MockGallerySeeder]도 같은 이유로 같은 관문을 지난다.
     */
    @Transactional
    fun create(userId: Long, request: CreateGalleryRequest): GalleryResponse {
        val studio = studioWriteAdmission.requireWritableByUserId(userId)

        val gallery = galleryRepository.save(
            Gallery.create(
                studioId = studio.requiredId,
                title = request.title,
                selectionDeadline = request.selectionDeadline,
                maxSelectablePhotoCount = request.maxSelectablePhotoCount,
                at = ZonedDateTime.now(clock),
            ),
        )
        return GalleryResponse.from(gallery)
    }

    @Transactional(readOnly = true)
    fun findAllVisibleTo(userId: Long): List<GalleryResponse> {
        val asPhotographer = studioRepository.findByUserId(userId)
            ?.let { galleryRepository.findAllByStudioId(it.requiredId) }
            .orEmpty()

        val memberGalleryIds = galleryMemberRepository.findAllByUserId(userId).map { it.galleryId }
        val asCouple = galleryRepository.findAllById(memberGalleryIds)
            .filter { it.isVisibleToMember }

        // 자기 갤러리 초대는 GalleryAccessPolicy.requireNotPhotographer가 막지만,
        // 그 규칙이 생기기 전 데이터까지 같은 갤러리를 두 번 그리게 두지는 않는다.
        return (asPhotographer + asCouple)
            .distinctBy { it.requiredId }
            .map(GalleryResponse::from)
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): GalleryResponse =
        GalleryResponse.from(galleryAccessPolicy.requireViewer(galleryId, userId))

    @Transactional
    fun changeMaxSelectablePhotoCount(
        galleryId: Long,
        userId: Long,
        request: ChangeMaxSelectablePhotoCountRequest,
    ): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.changeMaxSelectablePhotoCount(request.maxSelectablePhotoCount)
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun open(galleryId: Long, userId: Long): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.open()
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun close(galleryId: Long, userId: Long): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.close()
        return GalleryResponse.from(gallery)
    }

    @Transactional
    fun reopen(galleryId: Long, userId: Long, request: ReopenGalleryRequest): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.reopen(request.selectionDeadline, ZonedDateTime.now(clock))
        return GalleryResponse.from(gallery)
    }
}
