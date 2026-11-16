package com.soma.wes.agent.dto.request

import com.soma.wes.agent.domain.AgentActionStep
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

@Schema(description = "확인한 실행 계획, 또는 실행 응답의 되돌리기(undo). 둘 다 같은 모양이다")
data class AgentActionRequest(

    @field:NotEmpty
    @field:Size(max = MAX_STEPS)
    @field:Schema(
        description = "순서대로 한 트랜잭션에서 실행한다. 하나라도 실패하면 아무것도 바뀌지 않는다. " +
            "op = SELECT_ADD·SELECT_REMOVE·MOVE, targetDetailFolderId는 MOVE에만(null이면 미분류)",
    )
    val steps: List<AgentActionStep>,
) {

    companion object {

        /** 되돌리기는 이동을 이전 폴더별로 나눠 담는다. 폴더 수만큼이면 충분하다. */
        const val MAX_STEPS = 100
    }
}
