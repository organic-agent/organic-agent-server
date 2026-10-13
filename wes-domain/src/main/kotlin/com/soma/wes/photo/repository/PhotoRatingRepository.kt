package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.PhotoRating
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoRatingRepository : JpaRepository<PhotoRating, Long> {

    /** 사진당 하나라 단건이다. 유니크 제약이 만드는 인덱스가 이 조회를 받는다. */
    fun findByPhotoId(photoId: Long): PhotoRating?

    /**
     * 목록 화면이 쓴다. 사진마다 [findByPhotoId]를 부르면 한 페이지 200장이 질의 200번이 된다.
     */
    fun findAllByPhotoIdIn(photoIds: Collection<Long>): List<PhotoRating>

    /** 별점을 지운다. 매긴 적 없는 사진이면 0을 돌려준다 — 그 경우도 호출자가 성공으로 본다. */
    fun deleteByPhotoId(photoId: Long): Long
}
