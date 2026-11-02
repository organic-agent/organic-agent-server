package com.soma.wes.selection.service

import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.selection.dto.response.SelectionSyncResponse
import com.soma.wes.selection.repository.SelectionSyncRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SelectionSyncService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val selectionSyncRepository: SelectionSyncRepository,
) {
    /** 부부 참여자만 변경 번호를 읽는다. 시각·서버 메모리를 커서로 쓰면 삭제나 서버 교체를 놓친다. */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): SelectionSyncResponse {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        val revision = selectionSyncRepository.findByGalleryId(galleryId)
        return SelectionSyncResponse(
            selectionRevision = revision.selectionRevision,
            ratingRevision = revision.ratingRevision,
        )
    }
}
