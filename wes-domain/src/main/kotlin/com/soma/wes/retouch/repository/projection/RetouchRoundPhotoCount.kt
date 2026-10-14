package com.soma.wes.retouch.repository.projection

/** 회차별 담긴 사진 수. 회차 목록 요약이 쓴다. */
interface RetouchRoundPhotoCount {
    val roundId: Long
    val photoCount: Long
}
