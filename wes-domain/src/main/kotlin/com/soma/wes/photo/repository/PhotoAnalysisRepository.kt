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
               a.subjects AS subjects, a.burstId AS burstId, a.burstRank AS burstRank, a.subScores AS subScores
        FROM PhotoAnalysis a
        WHERE a.photoId IN (SELECT p.id FROM Photo p WHERE p.galleryId = :galleryId)
          AND a.pipelineVersion IS NOT NULL AND a.technicalPct IS NOT NULL AND a.aestheticPct IS NOT NULL
          AND a.embedding IS NOT NULL
        """,
    )
    fun findAllAnalyzedSummaryByGalleryId(@Param("galleryId") galleryId: Long): List<PhotoAnalysisSummary>

    /**
     * 갤러리에서 categorize 까지 끝난(임베딩 그룹이 있는) 분석 행을 폴더 계획에 필요한 컬럼만으로, 화면 순서([Photo.DISPLAY_ORDER]와 같은
     * display_order → id)로 읽는다. 벡터를 나르지 않고 Photo 엔티티도 따로 읽지 않는다 —
     * 7천 장 갤러리에서 엔티티째 읽기가 2분 가까이 걸렸다(#160).
     *
     * 분류가 덜 끝난 사진을 빼는 이유: 잡은 categorize 를 보낸 뒤에 올라온 사진을 기다리지 않고 닫힌다. 그 사진을 여기서 읽으면
     * 그룹이 없어 "기타" 폴더에 들어가 버리고, 다음 잡이 제자리에 넣을 기회가 사라진다.
     */
    @Query(
        """
        SELECT a.photoId AS photoId, a.embedGroupId AS embedGroupId, a.subjects AS subjects, a.burstId AS burstId
        FROM PhotoAnalysis a JOIN Photo p ON p.id = a.photoId
        WHERE p.galleryId = :galleryId AND a.embedGroupId IS NOT NULL
        ORDER BY p.displayOrder ASC, p.id ASC
        """,
    )
    fun findAllGroupingByGalleryIdOrderByDisplay(@Param("galleryId") galleryId: Long): List<PhotoAnalysisGrouping>

    /**
     * 갤러리에 폴더로는 분류됐지만 순위(백분위)가 아직인 사진이 있는가 — 화질 점수(2단계)가 다 차고 rank 모드가 돌기 전의 창이다.
     * 그 창에서 추천을 돌리면 순위 없는 사진이 재료에서 조용히 빠진다(#274 2물결).
     */
    @Query(
        """
        SELECT count(a) > 0
        FROM PhotoAnalysis a JOIN Photo p ON p.id = a.photoId
        WHERE p.galleryId = :galleryId AND a.embedGroupId IS NOT NULL AND a.technicalPct IS NULL AND a.error IS NULL
        """,
    )
    fun existsUnrankedByGalleryId(@Param("galleryId") galleryId: Long): Boolean

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
