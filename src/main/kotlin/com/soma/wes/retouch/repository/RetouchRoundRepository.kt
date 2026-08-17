package com.soma.wes.retouch.repository

import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.domain.RetouchRoundStatus
import org.springframework.data.jpa.repository.JpaRepository

interface RetouchRoundRepository : JpaRepository<RetouchRound, Long> {

    /**
     * 진행 중인 DRAFTING 회차를 찾는다. 갤러리당 하나뿐이라 상태로 찾아도 유일하다.
     */
    fun findByGalleryIdAndStatus(galleryId: Long, status: RetouchRoundStatus): RetouchRound?

    fun findAllByGalleryIdOrderByRoundNoAsc(galleryId: Long): List<RetouchRound>

    fun findFirstByGalleryIdOrderByRoundNoDesc(galleryId: Long): RetouchRound?

    /** 제출된(DRAFTING이 아닌) 회차 수. 계약 횟수와 비교하는 쪽이 쓴다. */
    fun countByGalleryIdAndStatusNot(galleryId: Long, status: RetouchRoundStatus): Long
}
