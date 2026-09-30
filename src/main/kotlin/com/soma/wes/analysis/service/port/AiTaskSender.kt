package com.soma.wes.analysis.service.port

import com.soma.wes.analysis.dto.AiTaskDto
import kotlin.reflect.KClass

/**
 * AI 작업 요청 하나([AiTaskDto])를 외부 실행기(Lambda 또는 로컬 서브프로세스)에 보낸다. 언제·무엇을 보낼지는 호출자
 * (EmbedDispatcher·ScoreWorkerSupervisor·AnalysisOrchestrator·관리자 재처리)가 정하고, 어느 작업이 어느 함수·스크립트로 가는지는
 * 어댑터가 정한다.
 */
interface AiTaskSender {

    /** 그 작업을 받을 실행기가 실제로 붙어 있는지. 로컬·테스트에는 없는 것이 정상이라 기동을 막지 않는다. */
    fun isAvailable(task: KClass<out AiTaskDto>): Boolean

    /** 작업을 맡기고 결과를 기다리지 않고 돌아온다. 전송 자체가 실패하면 도메인 예외를 던진다. */
    fun send(task: AiTaskDto)
}
