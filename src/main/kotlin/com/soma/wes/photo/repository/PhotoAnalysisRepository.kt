package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.repository.projection.PhotoAnalysisGrouping
import com.soma.wes.photo.repository.projection.PhotoAnalysisSummary
import com.soma.wes.photo.repository.projection.PhotoEmbedding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoAnalysisRepository : JpaRepository<PhotoAnalysis, Long> {

    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<PhotoAnalysis>

    /**
     * 갤러리의 분석 행 전부. `photo_analysis`에는 gallery_id가 없어(중복 저장 안 함) Photo를
     * 서브쿼리로 지난다 — `@SQLRestriction` 덕에 휴지통 사진의 행은 자연히 빠진다.
     */
    @Query("SELECT a FROM PhotoAnalysis a WHERE a.photoId IN (SELECT p.id FROM Photo p WHERE p.galleryId = :galleryId)")
    fun findAllByGalleryId(@Param("galleryId") galleryId: Long): List<PhotoAnalysis>

    /**
     * 갤러리에서 분석이 끝나고 벡터도 있는 행을 벡터 없이 읽는다. 조건은 [PhotoAnalysis.isAnalyzed]
     * + `embedding IS NOT NULL`과 같다 — 추천이 재료로 써도 되는 행의 정의를 쿼리로 옮긴 것이다.
     * 벡터는 [findAllEmbeddingByPhotoIdIn]으로 필요한 사진만 따로 읽는다.
     */
    @Query(
        """
        SELECT a.photoId AS photoId, a.technicalPct AS technicalPct, a.aestheticPct AS aestheticPct,
               a.subjects AS subjects, a.clusterId AS clusterId, a.clusterRank AS clusterRank, a.subScores AS subScores
        FROM PhotoAnalysis a
        WHERE a.photoId IN (SELECT p.id FROM Photo p WHERE p.galleryId = :galleryId)
          AND a.modelVersion IS NOT NULL AND a.technicalPct IS NOT NULL AND a.aestheticPct IS NOT NULL
          AND a.embedding IS NOT NULL
        """,
    )
    fun findAllAnalyzedSummaryByGalleryId(@Param("galleryId") galleryId: Long): List<PhotoAnalysisSummary>

    /**
     * 갤러리의 분석 행 전부를 폴더 계획에 필요한 컬럼만으로, 화면 순서([Photo.DISPLAY_ORDER]와 같은
     * display_order → id)로 읽는다. 벡터를 나르지 않고 Photo 엔티티도 따로 읽지 않는다 —
     * 7천 장 갤러리에서 엔티티째 읽기가 2분 가까이 걸렸다(#160).
     */
    @Query(
        """
        SELECT a.photoId AS photoId, a.embedGroupId AS embedGroupId, a.subjects AS subjects, a.clusterId AS clusterId
        FROM PhotoAnalysis a JOIN Photo p ON p.id = a.photoId
        WHERE p.galleryId = :galleryId
        ORDER BY p.displayOrder ASC, p.id ASC
        """,
    )
    fun findAllGroupingByGalleryIdOrderByDisplay(@Param("galleryId") galleryId: Long): List<PhotoAnalysisGrouping>

    /** 주어진 사진의 DINOv3 벡터. 벡터가 없는 행은 빠진다. */
    @Query(
        """
        SELECT a.photoId AS photoId, a.embedding AS embedding
        FROM PhotoAnalysis a
        WHERE a.photoId IN :photoIds AND a.embedding IS NOT NULL
        """,
    )
    fun findAllEmbeddingByPhotoIdIn(@Param("photoIds") photoIds: Collection<Long>): List<PhotoEmbedding>
}
