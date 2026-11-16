package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentTurnRole
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.dto.AgentTurnDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class AgentDecisionPromptUnitTest {

    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `부부가 한 말은 그대로 싣는다`() {
        // given
        val turn = AgentTurnDto(role = AgentTurnRole.USER, text = "노을 폴더 어디 있어?")

        // when
        val text = AgentDecisionPrompt.turn(turn, objectMapper)

        // then
        assertThat(text).isEqualTo("노을 폴더 어디 있어?")
    }

    @Test
    fun `도우미 답에는 종류와 번호 붙은 후보 폴더 id를 한 줄 덧붙인다`() {
        // given
        val turn = AgentTurnDto(
            role = AgentTurnRole.ASSISTANT,
            text = "이 중 어느 폴더일까요? 1. 야외 > 정원 · 2. 한강",
            kind = AgentReplyKind.FIND_FOLDER,
            folderCandidates = listOf(
                AgentDecisionDto.FolderTarget(conceptFolderId = 4, detailFolderId = 12),
                AgentDecisionDto.FolderTarget(conceptFolderId = 7),
            ),
        )

        // when
        val text = AgentDecisionPrompt.turn(turn, objectMapper)

        // then
        assertThat(text).isEqualTo("이 중 어느 폴더일까요? 1. 야외 > 정원 · 2. 한강\n[종류 find_folder · 후보 1=d12 2=c7]")
    }
}
