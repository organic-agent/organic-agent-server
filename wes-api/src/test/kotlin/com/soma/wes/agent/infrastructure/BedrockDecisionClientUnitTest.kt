package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * 실제 Bedrock으로 판단해 보는 회귀 가드(ADR-0001). Spring AI 2.0 · Boot 4.1 · AWS SDK 2.42 조합이 깨지면 여기서 먼저 보인다.
 * 돈이 들어 `BEDROCK_SPIKE=1`일 때만 돈다. 정확도는 질문 세트([BedrockDecisionEvalUnitTest])가 잰다.
 *
 *   BEDROCK_SPIKE=1 ./gradlew :wes-api:test --rerun --tests '*BedrockDecisionClientUnitTest*'
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_SPIKE", matches = "1")
class BedrockDecisionClientUnitTest {

    private val client = bedrockDecisionClient()

    private val context = AgentContextDto(
        concepts = listOf(
            concept(id = 1, name = "화이트스튜디오", shotOrder = 1, details = listOf(AgentContextDto.Detail(id = 11, name = "흰 벽 전신", photoCount = 120))),
            concept(id = 2, name = "야외정원", shotOrder = 4, details = listOf(AgentContextDto.Detail(id = 21, name = "노을", photoCount = 40))),
            concept(id = 3, name = "다크배경웨딩", shotOrder = 3, details = listOf(AgentContextDto.Detail(id = 31, name = "검은 배경 전신", photoCount = 50))),
            concept(id = 4, name = "블랙수트", shotOrder = 2, details = listOf(AgentContextDto.Detail(id = 41, name = "블랙수트 전신", photoCount = 80))),
        ),
        selectedCount = 30,
        maxSelectablePhotoCount = 50,
        screen = null,
    )

    companion object {

        private const val SAMPLES = 5
        private const val NANOS_PER_MILLI = 1_000_000
        private const val P50_LIMIT_MILLIS = 2_000L
        private const val MODEL = "global.anthropic.claude-sonnet-4-6"

        /** 실제 Bedrock(서울, global 프로필)을 부르는 판단기. 질문 세트 재측정도 같은 것을 쓴다. */
        fun bedrockDecisionClient(): BedrockDecisionClient {
            val chatModel = BedrockProxyChatModel.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .region(Region.AP_NORTHEAST_2)
                .timeout(Duration.ofSeconds(30))
                .options(BedrockChatOptions.builder().model(MODEL).build())
                .build()
            return BedrockDecisionClient(chatModel = chatModel, objectMapper = JsonMapper.builder().build(), modelId = MODEL)
        }

    }

    @Test
    fun `호칭과 폴더를 조건으로 옮기고 결정 한 번에 답한다`() {
        // when
        val groomOutdoor = client.decide("야외에서 남편 혼자 나온 사진만 보여줘", context)
        val coupleAdd = client.decide("블랙수트에서 우리 둘 나온 거 선택에 넣어줘", context)
        val count = client.decide("지금 몇 장 골랐어?", context)
        val myself = client.decide("나만 나온 사진 보여줘", context)

        // then
        assertSoftly { softly ->
            softly.assertThat(groomOutdoor).isInstanceOf(AgentDecisionDto.FilterPhotos::class.java)
            softly.assertThat((groomOutdoor as AgentDecisionDto.FilterPhotos).filter.subjects).containsExactly(AgentSubject.GROOM)
            softly.assertThat(groomOutdoor.filter.conceptFolderIds).containsExactly(2L)
            softly.assertThat((coupleAdd as AgentDecisionDto.PlanAction).op).isEqualTo(AgentActionOp.SELECT_ADD)
            softly.assertThat(coupleAdd.filter.subjects).containsExactly(AgentSubject.COUPLE)
            softly.assertThat((count as AgentDecisionDto.Stats).scope).isEqualTo(StatsScope.SELECTED)
            softly.assertThat(myself).isInstanceOf(AgentDecisionDto.Clarify::class.java)
        }
    }

    @Test
    fun `결정 한 번은 평소 2초 안이다`() {
        // when
        val millis = List(SAMPLES) {
            val startedAt = System.nanoTime()
            client.decide("남편 단독 컷만 보여줘", context)
            (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        }.sorted()

        // then
        println("[decide] p50=${millis[millis.size / 2]}ms max=${millis.last()}ms n=${millis.size}")
        assertThat(millis[millis.size / 2]).isLessThan(P50_LIMIT_MILLIS)
    }

    private fun concept(id: Long, name: String, shotOrder: Int, details: List<AgentContextDto.Detail>) =
        AgentContextDto.Concept(
            id = id,
            name = name,
            shotOrder = shotOrder,
            timeRange = null,
            photoCount = details.sumOf { it.photoCount },
            details = details,
        )
}
