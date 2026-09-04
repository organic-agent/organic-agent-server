package com.soma.wes.recommendation.dto

/** 같은 순간의 형제(연사) 컷 — 비교로 설득하기 위해 이유 문장 호출에 같이 보낸다. */
class SiblingImageDto(
    val photoId: Long,
    val whyNot: String,
    val image: ByteArray,
)
