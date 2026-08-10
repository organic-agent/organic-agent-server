package com.soma.wes.collab.controller.docs

import com.soma.wes.collab.dto.request.VoteCollabPhotoRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(
    name = "[Collab Guest]",
    description = "하객이 협업 링크로 의견을 남기는 API. 로그인은 필요 없지만 " +
        "입장할 때 받은 X-Guest-Token은 반드시 있어야 한다.",
)
interface CollabFeedbackControllerDocs {

    @Operation(
        summary = "사진에 댓글 남기기",
        description = "수정은 없다 — 지우고 다시 쓴다. 토큰이 없거나 이 세션의 것이 아니면 401이고, " +
            "화면은 그때 닉네임 입력을 띄우면 된다.",
        parameters = [
            Parameter(
                name = "X-Guest-Token",
                `in` = ParameterIn.HEADER,
                description = "입장(POST /api/v1/collab/{shareToken}/guests)할 때 받은 하객 토큰. 없으면 401이다.",
                required = true,
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "작성 성공"),
        ApiResponse(
            responseCode = "400",
            description = "댓글이 비었거나 500자를 넘음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "댓글 형식",
                            value = """{"code": "COLLAB_400_2", "message": "댓글은 1자 이상 500자 이하여야 합니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "401",
            description = "하객 토큰이 없거나 이 세션의 것이 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "입장하지 않음",
                            value = """{"code": "COLLAB_401_1", "message": "닉네임을 먼저 입력해 주세요."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "지금은 의견을 받지 않는 갤러리",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "마감됨",
                            value = """{"code": "COLLAB_403_2", "message": "지금은 의견을 남길 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements // 로그인 토큰이 아니라 하객 토큰을 쓰는 API다.
    fun writeComment(
        shareToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        request: WriteCollabCommentRequest,
    ): ResponseEntity<CollabCommentResponse>

    @Operation(
        summary = "내 댓글 지우기",
        description = "직접 남긴 댓글만 지울 수 있다. 남의 댓글이 불편하다면 부부에게 알려야 하고, " +
            "치우는 것은 부부와 담당 작가의 몫이다 — 하객 토큰은 브라우저에 저장된 값이라 " +
            "그것 하나로 남의 글을 지우게 두면 링크를 가진 누구나 댓글창을 비울 수 있다.",
        parameters = [
            Parameter(
                name = "X-Guest-Token",
                `in` = ParameterIn.HEADER,
                description = "입장(POST /api/v1/collab/{shareToken}/guests)할 때 받은 하객 토큰. 없으면 401이다.",
                required = true,
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "403",
            description = "남이 쓴 댓글",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "내 댓글이 아님",
                            value = """{"code": "COLLAB_403_3", "message": "직접 남긴 댓글만 지울 수 있습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 세션의 댓글이 아니거나 존재하지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "댓글 없음",
                            value = """{"code": "COLLAB_404_3", "message": "존재하지 않는 댓글입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements
    fun deleteComment(shareToken: String, commentId: Long, guestToken: String?): ResponseEntity<Unit>

    @Operation(
        summary = "사진에 반응 남기기",
        description = "GOOD·SOSO·BAD 중 하나를 남긴다. 하객 한 사람의 표는 하나라 다시 보내면 " +
            "새 표가 쌓이는 것이 아니라 덮어쓴다 — 새로고침할 때마다 표가 늘면 그 수를 보고 " +
            "사진을 고르는 부부가 속는다. PUT인 것도 그래서다.",
        parameters = [
            Parameter(
                name = "X-Guest-Token",
                `in` = ParameterIn.HEADER,
                description = "입장(POST /api/v1/collab/{shareToken}/guests)할 때 받은 하객 토큰. 없으면 401이다.",
                required = true,
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "반응 저장 성공"),
        ApiResponse(
            responseCode = "401",
            description = "하객 토큰이 없거나 이 세션의 것이 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "입장하지 않음",
                            value = """{"code": "COLLAB_401_1", "message": "닉네임을 먼저 입력해 주세요."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 세션에 담기지 않은 사진",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "사진 없음",
                            value = """{"code": "COLLAB_404_2", "message": "협업 세션에 없는 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements
    fun vote(
        shareToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        request: VoteCollabPhotoRequest,
    ): ResponseEntity<Unit>

    @Operation(
        summary = "반응 취소",
        description = "눌렀던 반응을 거둔다. 누른 적이 없어도 204다 — 목적은 '표가 없는 상태'이고 " +
            "그건 이미 이뤄져 있다.",
        parameters = [
            Parameter(
                name = "X-Guest-Token",
                `in` = ParameterIn.HEADER,
                description = "입장(POST /api/v1/collab/{shareToken}/guests)할 때 받은 하객 토큰. 없으면 401이다.",
                required = true,
            ),
        ],
    )
    @ApiResponses(ApiResponse(responseCode = "204", description = "취소 성공"))
    @SecurityRequirements
    fun cancelVote(shareToken: String, collabPhotoId: Long, guestToken: String?): ResponseEntity<Unit>
}
