package com.soma.wes.collab.controller.docs

import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.response.CollabCommentPageResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
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
    description = "하객이 협업 링크로 들어와 보는 API. 로그인하지 않고 부른다. " +
        "닉네임을 적고 받은 하객 토큰(X-Guest-Token)은 조회에서는 선택이고, 의견을 남길 때만 필수다.",
)
interface CollabShareControllerDocs {

    @Operation(
        summary = "협업 링크 열기",
        description = """
            하객이 링크를 눌렀을 때 처음 부르는 API다. 하객 토큰 없이 부른다.

            응답의 `writable`이 false면 부부가 이미 고르기를 끝낸 것이라 보기만 된다 —
            화면은 그때 댓글창과 반응 버튼을 감추면 된다. 이 값을 프론트가 마감 시각으로
            따로 계산하지 않는 것이 중요하다. 서버가 막는 기준과 어긋나면 하객은 열려 있는
            입력창에 쓴 글을 403으로 돌려받는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "발급한 적 없는 토큰",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "링크 없음",
                            value = """{"code": "COLLAB_404_1", "message": "존재하지 않는 협업 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "410",
            description = "부부가 거둬들인 링크. 404와 나눠 두어야 \"새 링크를 받으세요\"를 안내할 수 있다",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "폐기됨",
                            value = """{"code": "COLLAB_410_1", "message": "더 이상 사용할 수 없는 협업 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements // 로그인하지 않은 하객이 부르는 API다.
    fun open(shareToken: String): ResponseEntity<CollabLandingResponse>

    @Operation(
        summary = "하객 입장(닉네임 등록)",
        description = """
            닉네임을 적으면 서버가 하객 토큰을 발급한다. 이후 댓글·반응 요청의 `X-Guest-Token`
            헤더에 그대로 실으면 된다.

            토큰을 클라이언트가 만들지 않는 이유는 이것이 약하게나마 "본인"의 근거이기 때문이다.
            값을 스스로 정할 수 있으면 남의 이름으로 글을 쓰거나 남의 표를 뒤집을 수 있다.
            브라우저에 보관해야 다음에 열었을 때 같은 사람으로 이어진다 — 잃어버리면 다시
            입장하면 되고, 그때는 새 사람이 된다.

            의견을 받지 않는 상태(마감·마감된 갤러리)라면 입장 자체가 403이다. 들여보내 봐야
            할 수 있는 일이 없는데 닉네임만 받아두면, 하객은 그것을 글을 쓸 수 있다는 뜻으로 읽는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "입장 성공"),
        ApiResponse(
            responseCode = "400",
            description = "닉네임이 비었거나 20자를 넘음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "닉네임 형식",
                            value = """{"code": "COLLAB_400_1", "message": "닉네임은 1자 이상 20자 이하여야 합니다."}""",
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
    @SecurityRequirements
    fun enter(shareToken: String, request: EnterCollabRequest): ResponseEntity<CollabGuestResponse>

    @Operation(
        summary = "하객이 보는 사진 목록",
        description = """
            부부가 담은 사진만 온다. 사진마다 지금까지 모인 반응 수와 댓글 수가 함께 온다.

            `X-Guest-Token`을 보내면 `myReaction`에 자기가 누른 반응이 채워진다. 없어도
            사진은 그대로 보인다 — 사진을 보기도 전에 닉네임부터 받게 하지 않으려는 것이다.

            사진의 `score`(별점)는 늘 null이다. 별점은 부부와 작가가 고르며 서로에게 남기는
            표시라 하객에게 내보내지 않는다. viewUrl은 서명된 임시 URL이라 viewUrlTtlSeconds가
            지나면 만료되고, 그전에 목록을 다시 부르면 새 URL이 온다.
        """,
        parameters = [
            Parameter(
                name = "X-Guest-Token",
                `in` = ParameterIn.HEADER,
                description = "입장할 때 받은 하객 토큰. 없으면 익명으로 본다.",
                required = false,
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "페이지 요청이 올바르지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "음수 페이지",
                            value = """{"code": "COLLAB_400_7", "message": "페이지 번호는 0 이상이어야 합니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements
    fun listPhotos(
        shareToken: String,
        guestToken: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "사진에 달린 댓글 목록",
        description = "최근에 쓴 것이 위로 온다. `X-Guest-Token`을 보내면 자기가 쓴 댓글에 " +
            "mine=true가 붙어, 화면이 삭제 버튼을 거기에만 그릴 수 있다.",
        parameters = [
            Parameter(
                name = "X-Guest-Token",
                `in` = ParameterIn.HEADER,
                description = "입장할 때 받은 하객 토큰. 없으면 mine이 전부 false다.",
                required = false,
            ),
        ],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
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
    fun listComments(
        shareToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<CollabCommentPageResponse>
}
