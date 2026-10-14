package com.soma.wes.gallery.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.dto.response.GalleryInviteAcceptResponse
import com.soma.wes.gallery.dto.response.GalleryInviteResponse
import com.soma.wes.gallery.dto.response.GalleryInvitePreviewResponse
import com.soma.wes.gallery.dto.request.IssueGalleryInviteRequest
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
        summary = "초대 링크 발급(재발급)",
        description = "담당 작가만 발급할 수 있다. 응답의 inviteUrl을 그대로 예비 부부에게 전달하면 된다. " +
            "한 링크를 신랑과 신부가 각자 눌러 들어오며, 갤러리 정원은 2명이다. " +
            "유효 기간은 7일이다. **갤러리에 살아 있던 링크는 이 요청으로 폐기된다** — " +
            "갤러리당 유효한 링크는 항상 하나뿐이라, 다시 발급하면 이전에 전달한 링크는 즉시 쓸 수 없게 된다.",
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
    fun issue(
        loginUser: LoginUser,
        galleryId: Long,
        request: IssueGalleryInviteRequest?,
    ): ResponseEntity<GalleryInviteResponse>

    @Operation(
        summary = "현재 초대 링크 조회",
        description = "담당 작가와 갤러리 참여자(부부·개인 갤러리 파트너)가 조회할 수 있다. 갤러리에 살아 있는 링크 하나가 온다. " +
            "부부는 이 링크를 파트너에게 직접 건넨다. 직원 초대(STUDIO_MEMBER) 링크는 작가에게만 보이고 부부에게는 404다. " +
            "만료된 링크도 폐기 전까지는 함께 오며 status(ACTIVE·EXPIRED)로 구분한다 — " +
            "다시 발급해야 하는 상황인지 화면에서 알 수 있어야 하기 때문이다. " +
            "아직 한 번도 발급하지 않았거나 폐기만 해둔 상태면 404다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가도 갤러리 참여자도 아님",
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
            description = "살아 있는 링크가 없음. 발급 버튼을 보여주면 되는 정상 상태다",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "링크 없음",
                            value = """{"code": "GALLERY_404_3", "message": "존재하지 않는 초대 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun getCurrent(loginUser: LoginUser, galleryId: Long): ResponseEntity<GalleryInviteResponse>

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
            "담당 작가는 자기 갤러리의 초대를 수락할 수 없다. " +
            "갤러리 정원은 2명(부부)이며, 초대 사용 횟수나 정원을 모두 쓰면 409다 — " +
            "이미 멤버인 사람의 재요청은 정원과 무관하게 200이다.",
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
            responseCode = "409",
            description = "초대 사용 횟수 또는 갤러리 정원 소진",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "초대 소진",
                            value = """{"code": "GALLERY_409_1", "message": "초대 링크의 사용 가능 횟수를 모두 소진했습니다."}""",
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
                            value = """{"code": "GALLERY_404_4", "message": "유효하지 않은 초대 링크입니다."}""",
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

    @Operation(summary = "개인 파트너 초대 수락", description = "PERSONAL_PARTNER 초대만 허용하고 소유자를 포함한 정원 2명을 검증한다.")
    fun acceptPartner(loginUser: LoginUser, token: String): ResponseEntity<GalleryInviteAcceptResponse>

    @Operation(
        summary = "초대 미리보기",
        description = "수락 전에 스튜디오·갤러리와 초대 종류, 만료·폐기·정원·기존 소속 상태를 확인한다.",
    )
    fun preview(loginUser: LoginUser, token: String): ResponseEntity<GalleryInvitePreviewResponse>
}
