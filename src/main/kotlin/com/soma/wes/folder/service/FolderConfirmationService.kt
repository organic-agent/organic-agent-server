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

// [REFACTOR-RENAME 2026-09-27] FolderOrganization* → FolderConfirmation* (클래스 이름만 변경, 동작·REST 경로 동일)
/** 분류 결과를 제품의 고정 폴더 구조로 확정하는 1회 전이. 최신 분석을 다시 실행하지 않는다. */
@Service
class FolderConfirmationService(
    private val accessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    // [REFACTOR-CONFIRM 2026-09-27] FolderService 의존 제거 → 컨셉 조회 + FolderViewAssembler.
    // folderService.list(galleryId, userId)는 자체 인가(requireViewer)를 한 번 더 돌렸다.
    private val conceptRepository: ConceptFolderRepository,
    private val viewAssembler: FolderViewAssembler,
    // [REFACTOR-SUPPORT 2026-09-27] AiFolderMaterializeService.materializeFromAnalysis → folder/support/AiFolderMaterializer.materialize
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {
    // [REFACTOR-CONFIRM 2026-09-27] 메서드 이름 save → confirm (동작 동일)
    @Transactional
    fun confirm(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        accessPolicy.requireSelectionEditor(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.markFoldersSaved(ZonedDateTime.now(clock))
        // [REFACTOR-CONFIRM 2026-09-27] folderService.list(galleryId, userId) → 컨셉 조회 + viewAssembler.toResponses.
        // 빠진 requireViewer는 위 requireSelectionEditor가 포함한다(OPEN 갤러리의 초대 멤버·개인 참여자만 통과) — 인가 결과 동일.
        val existing = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId)
        val result = if (existing.isNotEmpty()) {
            viewAssembler.toResponses(existing)
        } else {
            aiFolderMaterializer.materialize(galleryId)
        }
        gallery.markSelectionInProgress()
        // [REFACTOR-CONFIRM 2026-09-27] 조건문 `if (existing.isNotEmpty())` 제거 — 물질화 경로가 내부에서 이미 기록하는지
        // 알 필요가 없게 항상 기록한다. recordGallery는 greatest() UPSERT라 두 번 불려도 결과가 같다.
        activityRecorder.recordGallery(galleryId)
        return result
    }
}
