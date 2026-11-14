package com.soma.wes.agent.dto.request

import com.soma.wes.agent.domain.PhotoFilter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "셀렉 도우미에게 묻는 질문 하나. 이전 대화는 기억하지 않는다 — 지금 화면을 같이 보내 \"지금 보이는 거\"를 풀게 한다")
data class AgentMessageRequest(

    @field:NotBlank
    @field:Size(max = MAX_TEXT_LENGTH)
    @field:Schema(description = "부부가 한 말 그대로", example = "블랙수트에서 우리 둘 나온 거 선택에 넣어줘")
    val text: String,

    @field:Schema(description = "지금 보고 있는 화면. 없으면 갤러리 전체를 보고 있는 것으로 본다")
    val screen: Screen? = null,
) {

    companion object {

        /** 말 한마디 길이. 붙여 넣은 긴 글은 판단 토큰만 늘린다. */
        const val MAX_TEXT_LENGTH = 300
    }

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
