package com.soma.wes.gallery.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.dto.response.GalleryInviteAcceptResponse
import com.soma.wes.gallery.dto.response.GalleryInviteResponse
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

@Tag(name = "[Gallery Invite]", description = "갤러리 초대 링크 API")
interface GalleryInviteControllerDocs {

    @Operation(
        summary = "초대 링크 발급",
        description = "담당 작가만 발급할 수 있다. 응답의 inviteUrl을 그대로 예비 부부에게 전달하면 된다. " +
            "한 링크를 여러 명이 쓸 수 있어서, 신랑에게 보낸 링크를 신부가 전달받아 눌러도 된다. " +
            "유효 기간은 7일이며, 만료되면 다시 발급한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "발급 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가가 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "권한 없음",
                            value = """{"code": "GALLERY_403_1", "message": "갤러리에 접근할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 갤러리",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "갤러리 없음",
                            value = """{"code": "GALLERY_404_1", "message": "존재하지 않는 갤러리입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun issue(loginUser: LoginUser, galleryId: Long): ResponseEntity<GalleryInviteResponse>

    @Operation(
        summary = "초대 링크 목록",
        description = "담당 작가만 조회할 수 있다. 만료·폐기된 링크도 함께 오며 status로 구분한다 " +
            "(ACTIVE·EXPIRED·REVOKED). 최근에 발급한 것이 먼저 온다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가가 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "권한 없음",
                            value = """{"code": "GALLERY_403_1", "message": "갤러리에 접근할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<GalleryInviteResponse>>

    @Operation(
        summary = "초대 링크 폐기",
        description = "링크가 엉뚱한 곳에 퍼졌을 때 거둬들인다. 이미 들어온 멤버는 그대로 남는다 — " +
            "내보내는 것은 멤버 삭제라는 별개의 동작이다. 이미 폐기한 링크를 다시 폐기해도 204다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "폐기 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가가 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "권한 없음",
                            value = """{"code": "GALLERY_403_1", "message": "갤러리에 접근할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 초대가 아니거나 존재하지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "초대 없음",
                            value = """{"code": "GALLERY_404_3", "message": "존재하지 않는 초대 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun revoke(loginUser: LoginUser, galleryId: Long, inviteId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "초대 수락",
        description = "링크를 눌러 갤러리에 들어온다. 갤러리 권한은 요구하지 않지만 로그인은 필요하다 — " +
            "누가 들어왔는지 남겨야 하기 때문이다. 아직 종류를 정하지 않은 계정은 이때 예비 부부(CLIENT)로 확정된다. " +
            "멱등하다: 같은 사람이 여러 번 눌러도 멤버는 하나이며 매번 200이다. " +
            "담당 작가는 자기 갤러리의 초대를 수락할 수 없다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수락 성공. 이미 멤버였어도 같은 응답이 온다"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가가 자기 갤러리 초대를 수락하려 함",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "작가는 수락 불가",
                            value = """{"code": "GALLERY_403_3", "message": "담당 작가는 초대를 수락할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "우리가 발급한 적 없는 토큰",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "초대 없음",
                            value = """{"code": "GALLERY_404_3", "message": "존재하지 않는 초대 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "410",
            description = "발급한 링크가 맞지만 지금은 쓸 수 없음. 404와 나눠 두어야 " +
                "\"작가에게 다시 요청하세요\"를 안내할 수 있다",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "만료",
                            value = """{"code": "GALLERY_410_1", "message": "만료된 초대 링크입니다."}""",
                        ),
                        ExampleObject(
                            name = "폐기",
                            value = """{"code": "GALLERY_410_2", "message": "더 이상 사용할 수 없는 초대 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun accept(loginUser: LoginUser, token: String): ResponseEntity<GalleryInviteAcceptResponse>
}
