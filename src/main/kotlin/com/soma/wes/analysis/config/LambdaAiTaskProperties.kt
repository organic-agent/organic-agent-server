package com.soma.wes.analysis.config

import com.soma.wes.analysis.dto.AiTaskDto
import kotlin.reflect.KClass
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 운영 실행기(`LambdaAiTaskSender`)가 AI 작업마다 부를 Lambda 함수 이름. prod는 Parameter Store가 채운다.
 *
 * prefix가 [AnalysisProperties]와 같아 설정 키는 `app.analysis.*-function-name` 그대로다 — 두 클래스가 각자 자기 필드만 가져간다.
 * 함수 이름이 비어 있는 것은 오류가 아니다. 로컬·테스트에는 Lambda가 없는 것이 정상이라 기동을 막지 않고,
 * 실제로 부르려는 순간에 실패한다.
 */
@ConfigurationProperties(prefix = "app.analysis")
data class LambdaAiTaskProperties(
    /** embedder Lambda 함수 이름. 관리자 사진 교체([AiTaskDto.ExactPhoto])도 이 함수로 간다. */
    val embedderFunctionName: String = "",
    /** score Lambda 함수 이름 — GPU 워커가 없을 때의 폴백. */
    val scoreFunctionName: String = "",
    /** categorize Lambda 함수 이름. */
    val categorizeFunctionName: String = "",
) {

    val isEmbedderConfigured: Boolean
        get() = embedderFunctionName.isNotBlank()

    fun functionNameOf(task: KClass<out AiTaskDto>): String = when (task) {
        AiTaskDto.Embed::class, AiTaskDto.ExactPhoto::class -> embedderFunctionName
        AiTaskDto.Score::class -> scoreFunctionName
        AiTaskDto.Categorize::class, AiTaskDto.Rank::class -> categorizeFunctionName
        else -> error("모르는 AI 작업: $task")
    }
}
