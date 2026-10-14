package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.DetailFolderAssignment
import com.soma.wes.folder.repository.projection.PhotoPlacement
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

// [GLOSSARY-1 2026-09-27] PhotoFolderAssignmentRepository → DetailFolderAssignmentRepository (용어집 D9)
interface DetailFolderAssignmentRepository : JpaRepository<DetailFolderAssignment, Long> {
    fun findAllByDetailFolderId(detailFolderId: Long): List<DetailFolderAssignment>
    fun findAllByDetailFolderIdIn(detailFolderIds: Collection<Long>): List<DetailFolderAssignment>
    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<DetailFolderAssignment>
    fun findAllByGalleryIdAndPhotoIdIn(galleryId: Long, photoIds: Collection<Long>): List<DetailFolderAssignment>
    /** 갤러리에서 이미 폴더에 든 사진 id. 사진 id 수천 개를 IN으로 보내는 대신 gallery_id 한 번으로 읽는다. */
    @Query("SELECT a.photoId FROM DetailFolderAssignment a WHERE a.galleryId = :galleryId")
    fun findAllPhotoIdsByGalleryId(@Param("galleryId") galleryId: Long): List<Long>
    /**
     * 갤러리에서 이미 폴더에 든 사진과 그 세부 폴더. AI 폴더 계획이 "새 사진과 같은 그룹의 옛 사진이 어디에 있나"를 보는 재료다.
     * 수천 행이라 엔티티가 아니라 두 컬럼만 읽는다.
     */
    @Query("SELECT a.photoId AS photoId, a.detailFolderId AS detailFolderId FROM DetailFolderAssignment a WHERE a.galleryId = :galleryId")
    fun findAllPlacementsByGalleryId(@Param("galleryId") galleryId: Long): List<PhotoPlacement>

    /** 한 분석 잡이 넣은 사진과 그 사진이 지금 든 세부 폴더. 물질화 재호출이 그 잡의 결과를 다시 조립하는 재료다. */
    @Query(
        "SELECT a.photoId AS photoId, a.detailFolderId AS detailFolderId FROM DetailFolderAssignment a " +
            "WHERE a.analysisJobId = :analysisJobId ORDER BY a.photoId",
    )
    fun findAllPlacementsByAnalysisJobId(@Param("analysisJobId") analysisJobId: Long): List<PhotoPlacement>

    fun deleteAllByDetailFolderId(detailFolderId: Long)
    fun deleteAllByDetailFolderIdIn(detailFolderIds: Collection<Long>)
}
