package com.soma.wes.agent.dto.request

import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentTurnRole
import com.soma.wes.agent.domain.PhotoFilter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "셀렉 도우미에게 묻는 질문 하나. 서버는 대화를 저장하지 않는다 — 웹이 지금 화면과 최근 대화(history)를 같이 보내 \"지금 보이는 거\"·\"두 번째 거\"를 풀게 한다")
data class AgentMessageRequest(

    @field:NotBlank
    @field:Size(max = MAX_TEXT_LENGTH)
    @field:Schema(description = "부부가 한 말 그대로", example = "블랙수트에서 우리 둘 나온 거 선택에 넣어줘")
    val text: String,

    @field:Schema(description = "지금 보고 있는 화면. 없으면 갤러리 전체를 보고 있는 것으로 본다")
    val screen: Screen? = null,

    @field:Schema(
        description = "최근 대화, 오래된 것부터 최대 6개(3턴). USER로 시작해 USER·ASSISTANT가 번갈아 오고 ASSISTANT로 끝난다. " +
            "어기면 400(AGENT_400_4). 새로고침하면 웹이 비운다 — 서버는 저장하지 않는다",
    )
    val history: List<Turn> = emptyList(),
) {

    companion object {

        /** 말 한마디 길이. 붙여 넣은 긴 글은 판단 토큰만 늘린다. */
        const val MAX_TEXT_LENGTH = 300

        /** 최근 3턴(질문·답 6개). 늘리면 캐시되지 않는 입력이 늘어 응답 시간·비용(QA-3·4)을 다시 잰다. */
        const val MAX_HISTORY = 6

        /** 도우미 답 한 줄. 점검 답처럼 줄이 여럿인 답도 이 안이다. */
        const val MAX_ASSISTANT_TEXT_LENGTH = 600
    }

    @Schema(description = "이전 대화 한 개. ASSISTANT는 도우미 응답에서 아래 칸만 골라 담는다 — 사진 id 목록은 싣지 않는다(서버가 지금 상태로 다시 센다)")
    data class Turn(

        @field:Schema(description = "USER = 부부가 한 말, ASSISTANT = 도우미 답")
        val role: AgentTurnRole,

        @field:Schema(description = "USER는 300자, ASSISTANT는 응답의 reply 그대로 600자까지", example = "노을이 들어간 폴더가 2개예요.\n1. 야외정원 > 노을\n2. 한강 > 노을")
        val text: String,

        @field:Schema(description = "ASSISTANT만 — 응답의 kind. USER면 비운다")
        val kind: AgentReplyKind? = null,

        @field:Schema(description = "ASSISTANT만 — 응답 folderCandidates의 폴더 id만(이름 빼고), reply의 번호 순서대로. 없으면 비운다")
        val folderCandidates: List<FolderRef> = emptyList(),

        @field:Schema(description = "ASSISTANT만 — 응답 view.filter 그대로. 없으면 null")
        val filter: PhotoFilter? = null,
    )

    @Schema(description = "가리킨 폴더. 세부 폴더면 detailFolderId까지")
    data class FolderRef(

        @field:Schema(description = "컨셉 폴더 id", example = "4")
        val conceptFolderId: Long,

        @field:Schema(description = "세부 폴더 id. null이면 컨셉 폴더 자체", example = "12")
        val detailFolderId: Long? = null,
    )

    @Schema(description = "지금 화면 — 열린 폴더와 걸린 필터")
    data class Screen(

        @field:Schema(description = "열린 컨셉 폴더 id", example = "4")
        val conceptFolderId: Long? = null,

        @field:Schema(description = "열린 세부 폴더 id")
        val detailFolderId: Long? = null,

        @field:Schema(description = "걸려 있는 필터. 도우미가 돌려준 view.filter를 사용자가 고친 것일 수 있다")
        val filter: PhotoFilter? = null,
    )
}
