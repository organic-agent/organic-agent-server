package com.soma.wes.analysis.service

import com.soma.wes.analysis.support.CategorizeStep
import com.soma.wes.analysis.support.EmbedStep
import com.soma.wes.analysis.support.FolderStep
import com.soma.wes.analysis.support.RankStep
import com.soma.wes.analysis.support.ScoreStep
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * AI 분석 파이프라인 — 업로드된 사진이 AI 폴더가 되기까지를 한 칸씩 민다. 순서는 실제 AI 단계 그대로다:
 * 임베딩([EmbedStep]) → 점수([ScoreStep]) → 묶고 이름 붙이기([CategorizeStep]) → 폴더로 만들고 잡 닫기([FolderStep]) →
 * 폴더 뒤에 추천 재료(백분위·순위) 채우기([RankStep]).
 *
 * 단계는 서로를 부르지 않는다. 각자 DB에서 자기 입력(사진·잡의 상태)을 집고, 조건부 UPDATE로 선점한 뒤 바깥(Lambda·GPU·folder)에
 * 맡긴다. 상태가 전부 DB에 있어 한 회차가 곧 기동 복구이고, 스윕 둘이 겹쳐도 선점에서 한쪽만 이긴다.
 * 이 클래스는 트랜잭션을 열지 않는다 — 단계 안의 문장·빈이 각자 짧은 트랜잭션이다.
 */
@Service
class AnalysisPipelineService(
    private val embedStep: EmbedStep,
    private val scoreStep: ScoreStep,
    private val categorizeStep: CategorizeStep,
    private val folderStep: FolderStep,
    private val rankStep: RankStep,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 모든 단계를 한 번씩. 한 단계의 실패는 가둬 뒤 단계를 막지 않고, 실패한 일은 다음 회차가 다시 집는다. */
    fun advance() {
        runStep("embed") { embedStep.advance() }
        runStep("score") { scoreStep.advance() }
        runStep("categorize") { categorizeStep.advance() }
        runStep("folder") { folderStep.advance() }
        runStep("rank") { rankStep.advance() }
    }

    private fun runStep(name: String, step: () -> Unit) {
        try {
            step()
        } catch (e: RuntimeException) {
            log.error("analysis {} 단계 실패 — 다음 회차에 다시 본다", name, e)
        }
    }
}
