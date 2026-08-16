package com.soma.wes.embedding.service

/**
 * 갤러리 하나의 임베딩 계산을 외부 실행기에 맡긴다.
 */
interface EmbeddingInvoker {

    /**
     * 실행기가 실제로 붙어 있는지.
     */
    val isAvailable: Boolean

    /**
     * 계산을 요청하고, 결과를 기다리지 않고 돌아온다.
     */
    fun invoke(galleryId: Long, force: Boolean)
}
