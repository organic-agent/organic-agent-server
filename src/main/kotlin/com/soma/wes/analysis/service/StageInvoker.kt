package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisStage

/**
 * 분석 단계 하나를 외부 실행기(Lambda 또는 로컬 서브프로세스)에 맡긴다. 세 단계가 같은 포트를 지난다 —
 * 어느 단계가 어느 함수·스크립트인지는 어댑터의 일이다.
 */
interface StageInvoker {

    /** 그 단계의 실행기가 실제로 붙어 있는지. 로컬·테스트에는 없는 것이 정상이라 기동을 막지 않는다. */
    fun isAvailable(stage: AnalysisStage): Boolean

    /** 실행을 요청하고 결과를 기다리지 않고 돌아온다. 호출 자체가 실패하면 도메인 예외를 던진다. */
    fun invoke(stage: AnalysisStage, jobId: Long, galleryId: Long, force: Boolean)
}
