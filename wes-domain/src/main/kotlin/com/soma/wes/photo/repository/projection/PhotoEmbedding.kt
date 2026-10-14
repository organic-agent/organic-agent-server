package com.soma.wes.photo.repository.projection

/** 사진 하나의 DINOv3 벡터. 벡터가 정말 필요한 사진(추천 범위 폴더)만 골라 읽을 때 쓴다. */
interface PhotoEmbedding {
    val photoId: Long
    val embedding: FloatArray
}
