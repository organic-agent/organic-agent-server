package com.soma.wes.recommendation.infrastructure

import com.soma.wes.photo.support.JpegResizer
import com.soma.wes.recommendation.config.LlmProperties
import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.support.ComparePrompt
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import tools.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import javax.imageio.ImageIO

/**
 * Bedrock 실호출 스파이크 — 이미지 블록 + `output_config` 스키마 강제가 이 스택에서 동작하는지 한 번 확인한다.
 * 비용이 들고 자격증명이 필요하므로 `WES_BEDROCK_SPIKE=1`일 때만 돈다(CI 제외). 결과는
 * `docs/plans/ai-feature-migration-to-wes.md` §8에 적는다.
 */
@EnabledIfEnvironmentVariable(named = "WES_BEDROCK_SPIKE", matches = "1")
class BedrockStructuredLlmClientSpikeTest {

    @Test
    fun `이미지 두 장과 스키마로 비교 판정을 받는다`() {
        val properties = LlmProperties(enabled = true)
        val client = BedrockRuntimeClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .region(Region.of(properties.region))
            .overrideConfiguration { it.retryStrategy(AwsRetryStrategy.doNotRetry()) }
            .build()
        val llm = BedrockStructuredLlmClient(client, properties, BedrockMessagesCodec(ObjectMapper()))
        val resizer = JpegResizer()

        val request = LlmJsonRequestDto(
            system = ComparePrompt.SYSTEM,
            parts = ComparePrompt.userParts(
                imageA = resizer.resize(solid(Color(200, 60, 60)), properties.imageLongEdge),
                imageB = resizer.resize(solid(Color(60, 60, 200)), properties.imageLongEdge),
                sentences = listOf("기술(화질) 백분위: 사진 a 가 20포인트 높다 (a 70 / b 50, 갤러리 안 순위)"),
            ),
            schema = ComparePrompt.SCHEMA,
            maxTokens = properties.compareMaxTokens,
            timeout = Duration.ofSeconds(30),
        )

        val out = llm.completeJson(request)
        println("SPIKE RESULT: $out")

        assertThat(out.path("chosen").asText()).isIn("a", "b")
        assertThat(out.path("confidence").asText()).isIn("clear", "slight")
        assertThat(out.path("reason").asText()).isNotBlank()
    }

    private fun solid(color: Color): ByteArray {
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { paint = color; fillRect(0, 0, 640, 480); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }
}
