package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.dto.LlmJsonRequestDto
import com.soma.wes.recommendation.dto.LlmPartDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/** 요청 body가 AI repo의 anthropic SDK가 보내던 모양(키 이름·블록 구조)과 같은지 — 스키마 강제와 이미지 블록이 핵심이다. */
class BedrockMessagesCodecUnitTest {

    private val objectMapper = ObjectMapper()
    private val codec = BedrockMessagesCodec(objectMapper)

    @Test
    fun `Messages body에 system·텍스트·이미지 블록·output_config 스키마를 담는다`() {
        // given
        val jpeg = byteArrayOf(1, 2, 3)
        val request = LlmJsonRequestDto(
            system = "너는 셀렉터다",
            parts = listOf(LlmPartDto.Text("사진 a:"), LlmPartDto.Image(jpeg), LlmPartDto.Text("재료")),
            schema = mapOf("type" to "object", "properties" to mapOf("chosen" to mapOf("enum" to listOf("a", "b")))),
            maxTokens = 512,
        )

        // when
        val body = objectMapper.readTree(codec.encode(request))

        // then
        val content = body.path("messages").get(0).path("content")
        assertSoftly { softly ->
            softly.assertThat(body.path("anthropic_version").asText()).isEqualTo("bedrock-2023-05-31")
            softly.assertThat(body.path("max_tokens").asInt()).isEqualTo(512)
            softly.assertThat(body.path("system").asText()).isEqualTo("너는 셀렉터다")
            softly.assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user")
            softly.assertThat(content.size()).isEqualTo(3)
            softly.assertThat(content.get(0).path("type").asText()).isEqualTo("text")
            softly.assertThat(content.get(1).path("type").asText()).isEqualTo("image")
            softly.assertThat(content.get(1).path("source").path("media_type").asText()).isEqualTo("image/jpeg")
            softly.assertThat(content.get(1).path("source").path("data").asText())
                .isEqualTo(Base64.getEncoder().encodeToString(jpeg))
            softly.assertThat(body.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema")
            softly.assertThat(body.path("output_config").path("format").path("schema").path("properties").path("chosen").path("enum").get(1).asText())
                .isEqualTo("b")
        }
    }

    @Test
    fun `응답에서 첫 텍스트 블록과 stop_reason·토큰을 읽는다`() {
        // given
        val json = """{"content":[{"type":"text","text":"{\"chosen\":\"a\"}"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""

        // when
        val decoded = codec.decode(json)

        // then
        assertSoftly { softly ->
            softly.assertThat(decoded.stopReason).isEqualTo("end_turn")
            softly.assertThat(decoded.inputTokens).isEqualTo(10)
            softly.assertThat(decoded.outputTokens).isEqualTo(5)
        }
        assertThat(codec.parseJson(decoded.text!!).path("chosen").asText()).isEqualTo("a")
    }
}
