package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.ExactPhotoInvoker
import com.soma.wes.analysis.service.ExactPhotoProcessingRequest
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 관리자 사진 교체 실행기 — 지원하지 않는다. `scripts/lambda/embedder.sh`는 갤러리 배정(`--photo-ids`)만 받는다.
 * "없음"을 정직하게 알려 관리자 잡이 `PHOTO_PROCESSING_NOT_CONFIGURED`로 닫히게 한다 — 조용히 삼키면 잡이 영원히 대기한다.
 */
@Component
@Profile("local")
class LocalProcessExactPhotoInvoker : ExactPhotoInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isAvailable: Boolean = false

    override fun invoke(request: ExactPhotoProcessingRequest) {
        log.error("로컬 프로필은 사진 단위 재처리를 지원하지 않는다: jobId={}, photoId={}", request.jobId, request.photoId)
        throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }
}
