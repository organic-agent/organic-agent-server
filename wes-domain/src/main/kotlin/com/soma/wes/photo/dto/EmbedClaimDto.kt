package com.soma.wes.photo.dto

/**
 * 임베더에 보낼 사진 한 장 — 배정과 함께 원본 위치를 넘긴다. 임베더가 DB 를 읽지 않고 원본을 받게 하려는 것이다(#286 (m) 결과 큐,
 * 임베더는 VPC 밖에서 DB 없이 돈다).
 */
data class EmbedClaimDto(
    val photoId: Long,
    val storageKey: String,
)
