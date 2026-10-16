package com.soma.wes.agent.controller.docs

import com.soma.wes.agent.dto.request.AgentActionRequest
import com.soma.wes.agent.dto.request.AgentMessageRequest
import com.soma.wes.agent.dto.response.AgentActionResponse
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.auth.domain.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Agent]", description = "셀렉 화면의 대화형 도우미 — 말로 폴더를 찾고, 사진을 거르고, 세고, 넣고 빼고 옮긴다")
interface AgentControllerDocs {

    @Operation(
        summary = "도우미에게 묻기",
        description = """
            질문 하나에 답 하나다. 이전 대화는 기억하지 않는다 — 지금 화면(열린 폴더·필터)을 screen으로 같이 보내면
            "지금 보이는 거"를 그것으로 푼다. 판단은 LLM 한 번(평소 1.5초 안팎)이고, 숫자와 답 문장은 서버가 센 값으로 만든다.

            이 창구는 아무것도 바꾸지 않는다. 넣기·빼기·옮기기는 kind=PLAN_ACTION으로 계획만 돌려주고,
            사용자가 [확인]을 누르면 action.steps를 실행 창구로 보낸다. 제출·철회·삭제·별점은 하지 않는다.

            부부만 쓴다. 신랑인지 신부인지는 묻지 않는다 — "나만 나온"처럼 화자를 가리키면 도우미가 되묻는다(kind=CLARIFY).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "답"),
        ApiResponse(responseCode = "400", description = "빈 질문이거나 300자를 넘는 질문", content = []),
        ApiResponse(responseCode = "403", description = "갤러리의 부부가 아니거나(작가 포함) 아직 열리지 않은 갤러리", content = []),
        ApiResponse(responseCode = "503", description = "도우미가 꺼져 있거나 판단(Bedrock)이 실패 — 잠시 뒤 다시 묻는다", content = []),
    )
    fun send(loginUser: LoginUser, galleryId: Long, request: AgentMessageRequest): ResponseEntity<AgentMessageResponse>

    @Operation(
        summary = "확인한 계획 실행·되돌리기",
        description = """
            질문 창구가 준 action.steps를, 또는 직전 실행 응답의 undo.steps를 그대로 보낸다. 동작을 순서대로 한 트랜잭션에서
            실행하고, 하나라도 거절되면 아무것도 바뀌지 않는다.

            쓰기는 기존 선택 앨범·폴더 이동 API와 같은 규칙으로 거절된다 — 제출된 선택, 계약 장수 초과, 갤러리에 없는 사진.
            이미 그 상태인 사진(이미 담긴 사진, 이미 그 폴더에 있는 사진)은 건너뛰고 applied에서 빠진다.

            되돌리기(undo)는 실행하는 순간에 실제로 바뀐 것으로 만든다. 빼고 다시 넣은 사진은 앨범 맨 뒤로 간다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "실행 결과와 되돌리기"),
        ApiResponse(responseCode = "400", description = "빈 계획, 사진 없는 동작, 이동이 아닌데 대상 폴더가 있음, 계약 장수 초과, 갤러리에 없는 사진", content = []),
        ApiResponse(responseCode = "403", description = "갤러리의 부부가 아니거나 선택 마감·갤러리 닫힘", content = []),
        ApiResponse(responseCode = "404", description = "없는 세부 폴더로 옮기기", content = []),
        ApiResponse(responseCode = "409", description = "이미 제출된 선택", content = []),
    )
    fun execute(loginUser: LoginUser, galleryId: Long, request: AgentActionRequest): ResponseEntity<AgentActionResponse>
}
