package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.ReopenGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime


@Service
class GalleryService(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val clock: Clock,
) {

    @Transactional
    fun create(userId: Long, request: CreateGalleryRequest): GalleryResponse {
        val studio = studioRepository.findByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)

        val gallery = galleryRepository.save(
            Gallery.create(
                studioId = checkNotNull(studio.id) { "저장되지 않은 스튜디오입니다." },
                title = request.title,
                selectionDeadline = request.selectionDeadline,
                at = ZonedDateTime.now(clock),
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

    /**
     * 갤러리를 열어 초대된 사람에게 보인다. DRAFT에서만 할 수 있다.
     *
     * 만드는 것과 여는 것이 나뉘어 있는 이유가 여기다. 작가는 사진을 다 올리고 정리한 뒤에
     * 열고, 그전까지 부부에게 이 갤러리는 없는 것과 같다.
     */
    @Transactional
    fun open(galleryId: Long, userId: Long): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.open()
        return GalleryResponse.from(gallery)
    }

    /**
     * 선택을 마감한다. 열람은 계속 되지만 고르거나 묶을 수는 없다 —
     * 정책의 `requireViewer`는 통과하고 `requireSelectable`은 막는 상태다.
     */
    @Transactional
    fun close(galleryId: Long, userId: Long): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.close()
        return GalleryResponse.from(gallery)
    }

    /**
     * 마감한 갤러리를 다시 연다. 기한을 요청에서 다시 받는다.
     *
     * 이전 기한을 그대로 두면 대부분 열자마자 `SELECTION_DEADLINE_PASSED`로 막힌다. 마감했다는
     * 것은 그 기한이 이미 지났거나 의미를 잃었다는 뜻이라, 다시 열 때 함께 정하게 한다.
     */
    @Transactional
    fun reopen(galleryId: Long, userId: Long, request: ReopenGalleryRequest): GalleryResponse {
        val gallery = galleryAccessPolicy.requirePhotographer(galleryId, userId)

        gallery.reopen(request.selectionDeadline, ZonedDateTime.now(clock))
        return GalleryResponse.from(gallery)
    }
}
