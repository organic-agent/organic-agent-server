package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.PhotoAnalysis
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
}
