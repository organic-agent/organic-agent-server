package com.soma.wes.analysis.dto

import com.soma.wes.analysis.domain.AnalysisStage

/** 트랜잭션 안에서 정한 "무엇을 부를지"를 트랜잭션 밖의 호출로 넘기는 전달용 값. */
data class StageDispatchDto(
    val jobId: Long,
    val galleryId: Long,
    val stage: AnalysisStage,
    val force: Boolean,
)
