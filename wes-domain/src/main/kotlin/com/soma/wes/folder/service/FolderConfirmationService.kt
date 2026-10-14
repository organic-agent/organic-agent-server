package com.soma.wes.folder.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/** 분류 결과를 제품의 고정 폴더 구조로 확정하는 1회 전이. 최신 분석을 다시 실행하지 않는다. */
@Service
class FolderConfirmationService(
    private val accessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val viewAssembler: FolderViewAssembler,
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {
    @Transactional
    fun confirm(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        accessPolicy.requireSelectionEditor(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.markFoldersSaved(ZonedDateTime.now(clock))
        val existing = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId)
        val result = if (existing.isNotEmpty()) {
            viewAssembler.toResponses(existing)
        } else {
            aiFolderMaterializer.materialize(galleryId)
        }
        gallery.markSelectionInProgress()
        activityRecorder.recordGallery(galleryId)
        return result
    }
}
