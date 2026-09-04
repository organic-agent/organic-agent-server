package com.soma.wes.recommendation.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Bedrock(Anthropic Messages) 호출 손잡이. 값의 정본은 AI repo `photoselect/docs/plan-v3-folder-compare.md`
 * §2·§3과 `config.py`의 `LlmKnobs`다 — 두 언어가 같은 값을 써야 하므로 바꿀 때는 그 문서를 먼저 고친다.
 *
 * [enabled]가 false면 LLM을 부르지 않는 구현이 꽂힌다 — 비교샷은 항상 템플릿 판정, 추천 이유는 항상
 * 폴백 문장이다(AI repo의 `llm=None` 경로와 같다). 로컬·테스트 기본값이고, prod는 Parameter Store가 켠다.
 */
@ConfigurationProperties(prefix = "app.llm")
data class LlmProperties(
    val enabled: Boolean = false,

    /** Bedrock 리전. 서울에는 Sonnet 온디맨드가 없어 [modelId]는 `global.` 크로스 리전 프로필이다. */
    val region: String = "ap-northeast-2",

    val modelId: String = "global.anthropic.claude-sonnet-4-6",

    /** LLM에 보내는 JPEG의 긴 변. 768이면 장당 800 토큰 안팎이다. */
    val imageLongEdge: Int = 768,

    val compareMaxTokens: Int = 1024,

    /** 이 시간 안에 판정이 안 오면 템플릿 판정으로 응답한다 — 사용자가 화면에서 기다리는 예산. */
    val compareTimeout: Duration = Duration.ofSeconds(8),

    /** 이유 문장 한 호출에 넣는 사진 수. 사진마다 이미지(본인 + 형제)가 붙으므로 작게. */
    val reasonsBatch: Int = 10,

    val reasonsMaxTokens: Int = 8192,

    /** 사진을 LLM에 보여 줄지. 끄면 텍스트 재료만 간다. */
    val reasonsVision: Boolean = true,

    /** 비교 대상으로 같이 보여 줄 형제(연사) 사진 최대 수. */
    val reasonsSiblingImages: Int = 2,
)
