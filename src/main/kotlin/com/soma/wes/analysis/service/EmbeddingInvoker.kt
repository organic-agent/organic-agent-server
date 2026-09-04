package com.soma.wes.analysis.service

/**
 * 임베더 Lambda를 갤러리 잡 밖에서 직접 부르는 포트. 관리자 경로(갤러리 재처리, 사진 교체 뒤 한 장 재처리)가 쓴다.
 * 작가의 "AI 분석"은 이 포트가 아니라 [AnalysisService]의 잡을 지난다 — [StageInvoker] 참조.
 */
interface EmbeddingInvoker {

    /** 실행기가 실제로 붙어 있는지. */
    val isAvailable: Boolean

    /** 갤러리 하나의 임베딩 계산을 요청하고, 결과를 기다리지 않고 돌아온다. */
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
