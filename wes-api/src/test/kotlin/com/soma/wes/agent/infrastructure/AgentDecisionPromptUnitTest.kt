package com.soma.wes.agent.infrastructure

import com.soma.wes.agent.domain.AgentPendingOp
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

    @Test
    fun `되묻기 답에는 하려던 일을 덧붙여 다음 말이 대상만이어도 잇게 한다`() {
        // given
        val turn = AgentTurnDto(
            role = AgentTurnRole.ASSISTANT,
            text = "지금 고른 12장은 1. 블랙수트 5장 · 2. 야외정원 7장이에요. 어디서 뺄까요?",
            kind = AgentReplyKind.CLARIFY,
            folderCandidates = listOf(AgentDecisionDto.FolderTarget(conceptFolderId = 4), AgentDecisionDto.FolderTarget(conceptFolderId = 2)),
            pendingOp = AgentPendingOp.SELECT_REMOVE,
        )

        // when
        val text = AgentDecisionPrompt.turn(turn, objectMapper)

        // then
        assertThat(text).endsWith("[종류 clarify · 후보 1=c4 2=c2 · 하려던 일 select_remove]")
    }
}
