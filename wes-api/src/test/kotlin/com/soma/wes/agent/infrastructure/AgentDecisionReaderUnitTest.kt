package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class AgentDecisionReaderUnitTest {

    private val reader = AgentDecisionReader(JsonMapper.builder().build())
    private val context = AgentContextDto(concepts = emptyList(), selectedCount = 0, maxSelectablePhotoCount = null, screen = null)

    private fun planAction(filter: String) =
        reader.read(AgentDecisionTools.PLAN_ACTION, """{"op":"select_remove","filter":$filter}""", text = null, context = context)

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"concepts":[2],"subjects":["bridee"]}""",
            """{"concepts":["c2"]}""",
            """{"concepts":[2],"orientation":"diagonal"}""",
            """{"concepts":[2],"addedBy":"mom"}""",
        ],
    )
    fun `조건 칸에 모르는 값이 있으면 그 칸을 버려 넓히지 않고 되묻는다`(filter: String) {
        // when
        val decision = planAction(filter)

        // then
        assertThat(decision).isEqualTo(AgentDecisionDto.Clarify(AgentDecisionReader.FILTER_UNCLEAR))
    }

    @Test
    fun `비운 칸은 조건이 없다는 뜻이라 계획을 만든다`() {
        // when
        val decision = planAction("""{"concepts":[2],"subjects":["bride"],"orientation":null,"addedBy":""}""")

        // then
        assertThat(decision).isInstanceOfSatisfying(AgentDecisionDto.PlanAction::class.java) { plan ->
            assertThat(plan.op).isEqualTo(AgentActionOp.SELECT_REMOVE)
            assertThat(plan.filter.conceptFolderIds).containsExactly(2L)
            assertThat(plan.filter.subjects).containsExactly(AgentSubject.BRIDE)
        }
    }

    @Test
    fun `세기와 거르기도 모르는 값이면 되묻는다`() {
        // when
        val stats = reader.read(AgentDecisionTools.STATS, """{"metric":"count","filter":{"subjects":["bridee"]}}""", text = null, context = context)
        val filtered = reader.read(AgentDecisionTools.FILTER_PHOTOS, """{"filter":{"subjects":["bridee"]}}""", text = null, context = context)

        // then
        assertThat(listOf(stats, filtered)).containsOnly(AgentDecisionDto.Clarify(AgentDecisionReader.FILTER_UNCLEAR))
    }
}
