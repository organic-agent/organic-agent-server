package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.PhotoRatingHistory
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoRatingHistoryRepository : JpaRepository<PhotoRatingHistory, Long> {

    /**
     * 사진 정보 패널의 별점 기록. 최신순 50건이면 충분하다 — 한 장을 두고 그 이상 바꾸는 일은 드물고, 화면도 다 보여 주지 않는다.
     * id 역순이 곧 적용 순서다(별점 쓰기는 사진 행을 잠가 한 줄로 세운다). `(photo_id, id DESC)` 인덱스가 받는다.
     */
    fun findTop50ByPhotoIdOrderByIdDesc(photoId: Long): List<PhotoRatingHistory>
}
