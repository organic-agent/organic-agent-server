package com.soma.wes.photo.dto

import com.soma.wes.photo.dto.response.PhotoMemoResponse

/**
 * 사진 응답을 만드는 데 DB에서 읽어 오는 몫 — 사진 id별 별점과 메모. 읽기 트랜잭션 안에서 채우고, 보기 주소 서명은 연결을 놓은 뒤
 * 이것과 함께 한다(`PhotoViewAssembler.toResponses`, #286 (b)).
 */
data class PhotoViewDataDto(
    val scores: Map<Long, Int>,
    val memos: Map<Long, PhotoMemoResponse>,
)
