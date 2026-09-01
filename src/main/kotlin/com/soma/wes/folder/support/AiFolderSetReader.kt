package com.soma.wes.folder.support

import com.soma.wes.folder.domain.FolderOrigin
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 다른 도메인(recommendation)이 AI 폴더 세트의 존재를 읽는 입구. repository를 직접 주입하는
 * 대신 이 리더를 지난다 — [com.soma.wes.recommendation.support.AiConceptAssignmentLoader]의 반대
 * 방향 짝이다.
 */
@Component
class AiFolderSetReader(
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
) {

    /**
     * 갤러리의 최신 AI 세트 키(`analysisJobId`). 세트가 없으면(만든 적 없거나 전부 지웠으면) null —
     * 호출자(추천 요청)가 409로 번역한다.
     */
    @Transactional(readOnly = true)
    fun latestSetJobId(galleryId: Long): Long? = photoFolderGroupRepository
        .findFirstByGalleryIdAndOriginAndAnalysisJobIdIsNotNullOrderByAnalysisJobIdDesc(galleryId, FolderOrigin.AI)
        ?.analysisJobId

    /** 지정한 세트가 이 갤러리에 살아 있는지. 지운 세트는 없는 것으로 본다. */
    @Transactional(readOnly = true)
    fun setExists(galleryId: Long, analysisJobId: Long): Boolean = photoFolderGroupRepository
        .existsByGalleryIdAndOriginAndAnalysisJobId(galleryId, FolderOrigin.AI, analysisJobId)
}
