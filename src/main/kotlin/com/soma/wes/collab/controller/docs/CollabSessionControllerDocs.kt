package com.soma.wes.collab.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(
    name = "[Collab Session]",
    description = "하객 협업 세션 관리 API. 부부가 하객에게 물어볼 사진을 담고 링크를 관리하며, " +
        "결과(반응 수·댓글 수)를 읽는다. 세션을 열고 사진을 담는 것은 예비 부부만 하고, " +
        "읽는 것은 담당 작가도 함께한다. " +
        "갤러리 하나에 세션이 여럿이다 — 묶음마다 물어볼 상대가 다르기 때문이고, " +
        "하객은 자기가 받은 링크의 사진만 본다.",
)
interface CollabSessionControllerDocs {

    @Operation(
        summary = "협업 세션 열기",
        description = """
            하객에게 보낼 링크를 새로 만든다. 예비 부부만 열 수 있다 — 하객에게 무엇을 물을지는
            고르는 과정의 일부라 작가가 대신 정하지 않는다.

            **부를 때마다 새 세션이다.** 갤러리 하나에 여러 개를 둘 수 있고, 이름(name)이 그것들을
            가른다. 폐기한 링크를 다시 살리려는 것이라면 이쪽이 아니라 `POST /{sessionId}/share-token`이다
            — 그쪽은 이미 받은 의견을 그대로 안고 간다.

            `folderId`를 주면 그 폴더에 담긴 사진으로 세션을 채운다. **복사이지 참조가 아니다** —
            세션을 연 뒤 폴더를 고치거나 지워도 하객이 보던 사진과 거기 달린 의견은 그대로다.
            폴더가 비어 있거나, 다른 갤러리의 폴더거나, 아직 올라오지 않은(PENDING) 사진이 섞여
            있으면 세션을 만들지 않고 요청 전체를 거절한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "세션을 열었음"),
        ApiResponse(
            responseCode = "400",
            description = "이름이 규칙에 맞지 않거나, 세션을 채울 수 없는 폴더",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "이름 규칙 위반",
                            value = """{"code": "COLLAB_400_9", "message": "협업 세션 이름은 1자 이상 100자 이하여야 합니다."}""",
                        ),
                        ExampleObject(
                            name = "다른 갤러리의 폴더",
                            value = """{"code": "COLLAB_400_10", "message": "이 갤러리의 폴더가 아닙니다."}""",
                        ),
                        ExampleObject(
                            name = "빈 폴더",
                            value = """{"code": "COLLAB_400_11", "message": "사진이 없는 폴더로는 협업 세션을 열 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "403",
            description = "예비 부부가 아니거나, 지금은 고를 수 없는 갤러리",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "부부가 아님(작가 포함)",
                            value = """{"code": "GALLERY_403_1", "message": "갤러리에 접근할 권한이 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "마감 기한이 지남",
                            value = """{"code": "GALLERY_403_4", "message": "사진 선택 마감 기한이 지났습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun open(
        loginUser: LoginUser,
        galleryId: Long,
        request: OpenCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "협업 세션 목록",
        description = "이 갤러리에 열린 링크 전부. 최근에 만든 것이 위로 온다. " +
            "담당 작가도 볼 수 있고 마감된 뒤에도 열린다. 하나도 없으면 빈 배열이다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<CollabSessionResponse>>

    @Operation(
        summary = "협업 세션 조회",
        description = "링크를 복사하는 화면이 부른다. 담당 작가도 볼 수 있고 마감된 뒤에도 열린다. " +
            "이 갤러리의 세션이 아니면 404다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 세션이 아니거나 존재하지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "세션 없음",
                            value = """{"code": "COLLAB_404_1", "message": "존재하지 않는 협업 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun get(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "협업 세션 이름 변경",
        description = "이름만 바꾼다. 링크(shareToken)는 그대로라 하객이 들고 있는 주소가 죽지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(
            responseCode = "400",
            description = "이름이 비었거나 100자를 넘김",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "이름 규칙 위반",
                            value = """{"code": "COLLAB_400_9", "message": "협업 세션 이름은 1자 이상 100자 이하여야 합니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun rename(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        request: RenameCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "협업 링크 폐기",
        description = "링크가 엉뚱한 곳에 퍼졌을 때 거둬들인다. 그 즉시 그 주소로는 아무것도 볼 수 없다. " +
            "담긴 사진과 이미 받은 의견은 지우지 않는다 — 끊는 것은 링크이지 하객이 남겨준 말이 아니다. " +
            "다시 쓰려면 `POST /{sessionId}/share-token`으로 새 토큰을 받는다. " +
            "이미 폐기한 세션을 또 폐기해도 204다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "폐기 성공"),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 세션이 아니거나 존재하지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "세션 없음",
                            value = """{"code": "COLLAB_404_1", "message": "존재하지 않는 협업 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun revoke(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "협업 링크 재발급",
        description = """
            폐기한 세션에 **새 주소**를 발급한다. 담긴 사진과 이미 받은 의견은 그대로 남는다 —
            세션을 새로 여는 것(`POST /collab-sessions`)과 다른 점이 이것이다.

            옛 토큰은 되살리지 않는다. 되살리면 그 링크가 퍼진 단톡방도 함께 되살아난다.
            폐기하지 않은 세션에 불러도 된다(폐기와 재발급을 한 번에 하는 셈이다).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재발급 성공"),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 세션이 아니거나 존재하지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "세션 없음",
                            value = """{"code": "COLLAB_404_1", "message": "존재하지 않는 협업 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun reissueToken(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "하객에게 보여줄 사진 담기",
        description = """
            갤러리 전체가 아니라 여기 담은 사진만 하객에게 보인다. 세션을 열 때 폴더로 채웠다면
            그 위에 더 담는 것이다.

            통째로 처리한다. 한 장이라도 이미 담겨 있으면 409로 요청 전체가 거절된다 —
            일부만 담아두면 화면에는 성공으로 보이고 어느 사진이 빠졌는지 아무도 모른다.
            아직 업로드가 끝나지 않은(PENDING) 사진도 담을 수 없다. 하객 화면에 깨진 이미지가
            뜨는데, 그것이 올라오는 중이라는 뜻임을 하객은 알 도리가 없다.

            같은 사진을 다른 세션에 담는 것은 막지 않는다. 같은 사진을 부모님께도 친구들에게도
            물을 수 있고, 그때 두 쪽의 의견은 따로 모인다.

            응답은 담고 난 뒤의 첫 페이지다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "담기 성공"),
        ApiResponse(
            responseCode = "400",
            description = "담을 수 없는 사진",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "다른 갤러리의 사진",
                            value = """{"code": "COLLAB_400_3", "message": "이 갤러리의 사진이 아닙니다."}""",
                        ),
                        ExampleObject(
                            name = "아직 올라오지 않은 사진",
                            value = """{"code": "COLLAB_400_4", "message": "아직 업로드가 끝나지 않은 사진은 담을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 담긴 사진이 섞여 있음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "중복",
                            value = """{"code": "COLLAB_409_1", "message": "이미 협업 세션에 담긴 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun addPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        request: AddCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "하객에게 보여줄 사진 빼기",
        description = "뺀 사진에 달린 댓글과 반응도 함께 사라진다 — 세션에서 뺀다는 것은 " +
            "'이 사진은 더 묻지 않겠다'는 뜻이다. 이미 빠진 id가 섞여 있어도 막지 않는다. " +
            "이 세션에서만 빠지고 같은 사진을 담은 다른 세션은 그대로다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "빼기 성공"))
    fun removePhotos(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        request: RemoveCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "하객 반응 결과 조회",
        description = "담긴 사진마다 GOOD·SOSO·BAD가 몇 개씩 모였고 댓글이 몇 개인지 온다. " +
            "부부가 결과를 읽는 화면이고 담당 작가도 같은 것을 본다. " +
            "이 세션에 모인 것만 나온다 — 같은 사진을 담은 옆 세션의 반응은 섞이지 않는다. " +
            "myReaction은 늘 null이다 — 부부와 작가는 하객이 아니라 반응을 남기지 않는다. " +
            "사진의 별점(score)도 늘 null이다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    fun listPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        page: Int,
        size: Int,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "하객 댓글 삭제",
        description = "부부와 담당 작가가 부적절한 댓글을 치운다. 남이 쓴 댓글도 지울 수 있고 " +
            "마감된 뒤에도 된다 — 하객이 자기 댓글을 지우는 것과는 다른 문이다. " +
            "이 세션의 댓글만 지운다. 같은 갤러리의 옆 세션 댓글도 여기서는 404다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
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
    fun deleteComment(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        commentId: Long,
    ): ResponseEntity<Unit>
}
