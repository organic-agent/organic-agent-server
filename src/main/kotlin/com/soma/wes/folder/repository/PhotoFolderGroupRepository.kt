package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.FolderOrigin
import com.soma.wes.folder.domain.PhotoFolderGroup
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface PhotoFolderGroupRepository : JpaRepository<PhotoFolderGroup, Long> {

    fun findAllByGalleryIdOrderByCreatedAtDesc(galleryId: Long): List<PhotoFolderGroup>

    /**
     * 갤러리의 최신 AI 세트에 속한 부모 하나. `analysisJobId`가 세트 키이자 시간 축이라 그것으로
     * 정렬한다 — 지운 세트는 `@SQLRestriction`이 걸러 주므로 "지금 화면에 있는 최신 세트"다.
     */
    fun findFirstByGalleryIdAndOriginAndAnalysisJobIdIsNotNullOrderByAnalysisJobIdDesc(
        galleryId: Long,
        origin: FolderOrigin,
    ): PhotoFolderGroup?

    fun existsByGalleryIdAndOriginAndAnalysisJobId(
        galleryId: Long,
        origin: FolderOrigin,
        analysisJobId: Long,
    ): Boolean

    /**
     * id만으로 찾지 않는다. 갤러리를 함께 걸어야 남의 갤러리 부모폴더 id를 자기 갤러리 경로로
     * 넘겨 건드리는 요청이 막힌다 — 인가는 경로의 galleryId로만 확인하기 때문이다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolderGroup?

    /** 관리자 재계산과 수동 폴더·사진 구조 변경을 이 부모 행 하나로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolderGroup?
}
