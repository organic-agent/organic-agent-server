package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class GalleryService(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
) {

    @Transactional
    fun create(userId: Long, request: CreateGalleryRequest): GalleryResponse {
        val studio = studioRepository.findByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)

        val gallery = galleryRepository.save(
            Gallery(
                studioId = checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." },
                title = request.title,
                selectionDeadline = request.selectionDeadline,
            ),
        )
        return GalleryResponse.from(gallery)
    }

    @Transactional(readOnly = true)
    fun findAllVisibleTo(userId: Long): List<GalleryResponse> {
        val ownStudio = studioRepository.findByUserId(userId)
        if (ownStudio != null) {
            val owned = galleryRepository.findAllByStudioId(
                checkNotNull(ownStudio.id) { "저장되지 않은 스튜디오입니다." },
            )
            return owned.map(GalleryResponse::from)
        }

        val galleryIds = galleryMemberRepository.findAllByUserId(userId).map { it.galleryId }
        // DRAFT는 초대된 사람에게 아직 보이지 않는다. 정책의 requireViewer와 같은 기준이다.
        return galleryRepository.findAllById(galleryIds)
            .filter { it.isVisibleToMember }
            .map(GalleryResponse::from)
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): GalleryResponse =
        GalleryResponse.from(galleryAccessPolicy.requireViewer(galleryId, userId))
}
