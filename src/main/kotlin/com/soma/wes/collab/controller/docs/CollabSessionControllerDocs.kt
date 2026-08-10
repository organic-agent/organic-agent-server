package com.soma.wes.collab.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
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
        "읽는 것은 담당 작가도 함께한다.",
)
interface CollabSessionControllerDocs {

    @Operation(
        summary = "협업 세션 열기",
        description = """
            하객에게 보낼 링크를 만든다. 예비 부부만 열 수 있다 — 하객에게 무엇을 물을지는
            고르는 과정의 일부라 작가가 대신 정하지 않는다.

            멱등하다. 이미 열려 있으면 같은 링크를 그대로 돌려주므로, 버튼을 두 번 눌러도
            하객이 들고 있는 링크가 죽지 않는다. 폐기한 세션에 다시 부르면 **새 토큰**으로
            다시 열리고, 그때도 담긴 사진과 이미 받은 의견은 그대로 남는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "세션이 열려 있음(새로 만들었거나 이미 있었거나)"),
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
    fun open(loginUser: LoginUser, galleryId: Long): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "협업 세션 조회",
        description = "링크를 복사하는 화면이 부른다. 담당 작가도 볼 수 있고 마감된 뒤에도 열린다. " +
            "아직 열지 않았으면 404다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "아직 세션을 열지 않은 갤러리",
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
    fun get(loginUser: LoginUser, galleryId: Long): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "협업 링크 폐기",
        description = "링크가 엉뚱한 곳에 퍼졌을 때 거둬들인다. 그 즉시 그 주소로는 아무것도 볼 수 없다. " +
            "담긴 사진과 이미 받은 의견은 지우지 않는다 — 끊는 것은 링크이지 하객이 남겨준 말이 아니다. " +
            "다시 열면 새 토큰이 나온다. 이미 폐기한 세션을 또 폐기해도 204다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "폐기 성공"),
        ApiResponse(
            responseCode = "404",
            description = "아직 세션을 열지 않은 갤러리",
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
    fun revoke(loginUser: LoginUser, galleryId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "하객에게 보여줄 사진 담기",
        description = """
            갤러리 전체가 아니라 여기 담은 사진만 하객에게 보인다.

            통째로 처리한다. 한 장이라도 이미 담겨 있으면 409로 요청 전체가 거절된다 —
            일부만 담아두면 화면에는 성공으로 보이고 어느 사진이 빠졌는지 아무도 모른다.
            아직 업로드가 끝나지 않은(PENDING) 사진도 담을 수 없다. 하객 화면에 깨진 이미지가
            뜨는데, 그것이 올라오는 중이라는 뜻임을 하객은 알 도리가 없다.

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
        request: AddCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "하객에게 보여줄 사진 빼기",
        description = "뺀 사진에 달린 댓글과 반응도 함께 사라진다 — 세션에서 뺀다는 것은 " +
            "'이 사진은 더 묻지 않겠다'는 뜻이다. 이미 빠진 id가 섞여 있어도 막지 않는다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "빼기 성공"))
    fun removePhotos(
        loginUser: LoginUser,
        galleryId: Long,
        request: RemoveCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "하객 반응 결과 조회",
        description = "담긴 사진마다 GOOD·SOSO·BAD가 몇 개씩 모였고 댓글이 몇 개인지 온다. " +
            "부부가 결과를 읽는 화면이고 담당 작가도 같은 것을 본다. " +
            "myReaction은 늘 null이다 — 부부와 작가는 하객이 아니라 반응을 남기지 않는다. " +
            "사진의 별점(score)도 늘 null이다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    fun listPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        page: Int,
        size: Int,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "하객 댓글 삭제",
        description = "부부와 담당 작가가 부적절한 댓글을 치운다. 남이 쓴 댓글도 지울 수 있고 " +
            "마감된 뒤에도 된다 — 하객이 자기 댓글을 지우는 것과는 다른 문이다.",
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
    fun deleteComment(loginUser: LoginUser, galleryId: Long, commentId: Long): ResponseEntity<Unit>
}
