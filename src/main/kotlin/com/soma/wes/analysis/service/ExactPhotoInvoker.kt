package com.soma.wes.analysis.service

/**
 * 관리자 사진 교체 뒤 정확히 한 리비전의 한 사진만 임베더에 다시 맡기는 포트. 갤러리 단위 호출은 없다 —
 * 작가의 "AI 분석"과 관리자 갤러리 재처리는 스윕의 임베더 배정([EmbedDispatcher])을 지난다.
 */
interface ExactPhotoInvoker {

    /** 실행기가 실제로 붙어 있는지. */
    val isAvailable: Boolean

    /** 처리를 요청하고 결과를 기다리지 않고 돌아온다. */
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
