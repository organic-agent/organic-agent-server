package com.soma.wes.category.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.category.dto.response.ConceptFolderResponse
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/** 분류 결과를 제품의 고정 폴더 구조로 확정하는 1회 전이. 최신 분석을 다시 실행하지 않는다. */
@Service
class FolderOrganizationService(
    private val accessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val categoryService: CategoryService,
    private val aiCategoryFolderService: AiCategoryFolderService,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {
    @Transactional
    fun save(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        accessPolicy.requireSelectionEditor(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.markFoldersSaved(ZonedDateTime.now(clock))
        val existing = categoryService.list(galleryId, userId)
        val result = if (existing.isNotEmpty()) existing else aiCategoryFolderService.materializeFromAnalysis(galleryId)
        gallery.markSelectionInProgress()
        // 새 AI 폴더를 만든 경우에는 같은 트랜잭션의 물질화 경로에서 이미 기록했다.
        if (existing.isNotEmpty()) activityRecorder.recordGallery(galleryId)
        return result
    }
}
