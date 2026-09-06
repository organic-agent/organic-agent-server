package com.soma.wes.recommendation.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Bedrock(Anthropic Messages) 호출 손잡이. AI repo의 `photoselect` 모듈(`LlmKnobs`)이 이 서버로 옮겨 오면서
 * 값의 정본도 여기다.
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

    /**
     * 이유 문장 한 배치의 예산. 배치는 사용자가 기다리지 않으므로 넉넉하되, 끝없이 물고 있지는 않게.
     * HTTP 소켓 읽기 타임아웃도 이 값으로 맞춘다([AwsBedrockConfig]) — SDK 기본 30초가 남아 있으면
     * 이미지 여러 장 + 수천 토큰 출력을 기다리다 끊긴다.
     */
    val reasonsTimeout: Duration = Duration.ofMinutes(3),

    /** 이유 문장 한 호출에 넣는 사진 수. 사진마다 이미지(본인 + 형제)가 붙으므로 작게. 10장이면 한 호출에 1분 안팎이다. */
    val reasonsBatch: Int = 10,

    val reasonsMaxTokens: Int = 8192,

    /** 사진을 LLM에 보여 줄지. 끄면 텍스트 재료만 간다. */
    val reasonsVision: Boolean = true,

    /** 비교 대상으로 같이 보여 줄 형제(연사) 사진 최대 수. */
    val reasonsSiblingImages: Int = 2,
)
