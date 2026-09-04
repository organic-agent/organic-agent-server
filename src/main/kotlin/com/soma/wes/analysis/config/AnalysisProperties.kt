package com.soma.wes.analysis.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 분석 파이프라인 오케스트레이션 설정. 임베더 함수·로컬 스크립트는 [EmbeddingProperties](`app.embedding.*`)에 그대로 있다 —
 * Parameter Store 키를 옮기지 않기 위해서다.
 *
 * 함수 이름이 비어 있는 것은 오류가 아니다. 로컬·테스트에는 Lambda가 없는 것이 정상이라 기동을 막지 않고,
 * 실제로 부르려는 순간에 실패한다.
 */
@ConfigurationProperties(prefix = "app.analysis")
data class AnalysisProperties(
    /** score Lambda 함수 이름. prod는 Parameter Store가 채운다. */
    val scoreFunctionName: String = "",
    /** categorize Lambda 함수 이름. */
    val categorizeFunctionName: String = "",
    /**
     * Lambda가 `ai_analysis_jobs.stage_status`·`heartbeat_at`을 쓰는가(AI repo Phase 0 이후).
     *
     * false면 옛 계약이다 — score가 `status`를 RUNNING으로 올리고 끝에서 categorize를 직접 체인 호출하며 categorize가
     * DONE을 찍는다. 이 서버는 SCORE를 한 번 보내고 그 뒤는 AI 쪽에 맡긴다(단계 전이·정체 감지 없음).
     * true면 이 서버가 단계마다 부르고, 하트비트로 정체를 잡고, DONE·FAILED를 닫는다.
     */
    val lambdaReportsStage: Boolean = false,
    /** EVENT를 보낸 뒤 이 시간 안에 단계가 잡히지 않으면 다시 보낸다. 컨테이너 Lambda의 콜드 스타트보다 길어야 한다. */
    val dispatchRetryAfter: Duration = Duration.ofMinutes(5),
    /** 하트비트가 이 시간 이상 끊기면 정체로 보고 단계를 다시 줄에 세운다. */
    val stallAfter: Duration = Duration.ofMinutes(20),
    /** 한 단계를 이 횟수까지 부른다. 넘으면 잡을 FAILED로 닫는다. */
    val maxAttempts: Int = 3,
) {

    val isScoreConfigured: Boolean
        get() = scoreFunctionName.isNotBlank()

    val isCategorizeConfigured: Boolean
        get() = categorizeFunctionName.isNotBlank()
}
