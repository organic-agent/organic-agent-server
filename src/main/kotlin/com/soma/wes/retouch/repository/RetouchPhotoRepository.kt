package com.soma.wes.retouch.repository

import com.soma.wes.retouch.domain.RetouchPhoto
import com.soma.wes.retouch.repository.projection.RetouchRoundPhotoCount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RetouchPhotoRepository : JpaRepository<RetouchPhoto, Long> {

    fun findAllByRoundId(roundId: Long): List<RetouchPhoto>

    fun findByRoundIdAndPhotoId(roundId: Long, photoId: Long): RetouchPhoto?

    fun findAllByRoundIdAndPhotoIdIn(roundId: Long, photoIds: Collection<Long>): List<RetouchPhoto>

    fun countByRoundId(roundId: Long): Long

    /** 0을 돌려주면 회차에 없는 사진이다 — 호출자가 404로 번역한다. */
    fun deleteByRoundIdAndPhotoId(roundId: Long, photoId: Long): Long

    /** 회차 목록 요약에 쓴다. 회차 수만큼 count 질의를 반복하지 않기 위한 한 번의 집계다. */
    @Query(
        "SELECT rp.roundId AS roundId, COUNT(rp.id) AS photoCount " +
            "FROM RetouchPhoto rp WHERE rp.galleryId = :galleryId GROUP BY rp.roundId",
    )
    fun countAllByGalleryIdGroupByRoundId(@Param("galleryId") galleryId: Long): List<RetouchRoundPhotoCount>
}
