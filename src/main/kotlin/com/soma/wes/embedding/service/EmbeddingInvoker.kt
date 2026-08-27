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

    /** 관리자 사진 교체 뒤 정확히 한 리비전의 한 사진만 처리한다. */
    fun invoke(request: ExactPhotoProcessingRequest)
}

data class ExactPhotoProcessingRequest(
    val jobId: Long,
    val attemptCount: Int,
    val jobType: String,
    val photoId: Long,
    val galleryId: Long,
    val storageKey: String,
    val revisionId: Long,
)
