package com.soma.wes.studio.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(name = "[Studio]", description = "스튜디오 API (사진작가 온보딩)")
interface StudioControllerDocs {

    @Operation(
        summary = "스튜디오 생성 (작가 온보딩 완료)",
        description = """
            소셜 로그인을 마친 사용자가 스튜디오를 만들어 온보딩을 끝낸다.
            **이 요청이 성공하면 사용자 종류가 PHOTOGRAPHER로 확정된다** — 종류만 정하는 API는 없다.

            갤러리는 작가 개인이 아니라 스튜디오에 속하므로, 이 단계를 거치지 않으면
            갤러리 생성이 STUDIO_404_1로 실패한다.

            초대 링크로 먼저 들어와 예비 부부(CLIENT)로 확정된 계정은 스튜디오를 만들 수 없다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공. 이 시점부터 GET /users/me의 userType이 PHOTOGRAPHER다."),
        ApiResponse(
            responseCode = "400",
            description = "주소 형식이 맞지 않거나 서비스 예약어임",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "쓸 수 없는 주소",
                            value = """{"code": "STUDIO_400_1", "message": "갤러리 주소는 소문자·숫자·하이픈으로 3~50자여야 하며, 사용할 수 없는 주소입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 스튜디오가 있거나, 주소가 겹치거나, 이미 예비 부부로 확정된 계정임",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "이미 온보딩을 끝냈다",
                            value = """{"code": "STUDIO_409_1", "message": "이미 스튜디오를 만들었습니다."}""",
                        ),
                        ExampleObject(
                            name = "주소 중복 — 다른 주소를 받아야 한다",
                            value = """{"code": "STUDIO_409_2", "message": "이미 사용 중인 갤러리 주소입니다."}""",
                        ),
                        ExampleObject(
                            name = "초대로 들어온 예비 부부 계정이다",
                            value = """{"code": "USER_409_1", "message": "이미 사용자 종류가 정해졌습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun create(loginUser: LoginUser, request: CreateStudioRequest): ResponseEntity<StudioResponse>

    @Operation(
        summary = "공개 주소 사용 가능 여부",
        description = """
            온보딩 화면이 입력 중에 물어본다. 형식·예약어 규칙은 생성 API와 같은 것을 쓰므로
            여기서 통과한 주소가 저장 단계에서 형식 때문에 거절되는 일은 없다.

            available이 true라도 생성이 반드시 성공하지는 않는다. 확인과 생성 사이에 다른
            사람이 같은 주소를 채갈 수 있고, 최종 판단은 생성 API의 STUDIO_409_2다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "확인 성공"),
        ApiResponse(
            responseCode = "400",
            description = "주소 형식이 맞지 않거나 서비스 예약어임",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "쓸 수 없는 주소",
                            value = """{"code": "STUDIO_400_1", "message": "갤러리 주소는 소문자·숫자·하이픈으로 3~50자여야 하며, 사용할 수 없는 주소입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun checkGalleryUrl(galleryUrl: String): ResponseEntity<GalleryUrlAvailabilityResponse>

    @Operation(summary = "내 스튜디오 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "아직 스튜디오를 만들지 않음(= 온보딩 미완료)",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "온보딩 미완료 — 스튜디오 생성 화면으로 보낼 것",
                            value = """{"code": "STUDIO_404_1", "message": "존재하지 않는 스튜디오입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun getMyStudio(loginUser: LoginUser): ResponseEntity<StudioResponse>

    @Operation(
        summary = "내 스튜디오 수정",
        description = "이름과 공개 주소를 바꾼다. 주소를 그대로 두고 이름만 바꾸는 것도 된다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "주소 형식이 맞지 않거나 서비스 예약어임",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "쓸 수 없는 주소",
                            value = """{"code": "STUDIO_400_1", "message": "갤러리 주소는 소문자·숫자·하이픈으로 3~50자여야 하며, 사용할 수 없는 주소입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "아직 스튜디오를 만들지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "스튜디오 없음",
                            value = """{"code": "STUDIO_404_1", "message": "존재하지 않는 스튜디오입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "다른 스튜디오가 이미 쓰는 주소",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "주소 중복",
                            value = """{"code": "STUDIO_409_2", "message": "이미 사용 중인 갤러리 주소입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun updateMyStudio(loginUser: LoginUser, request: UpdateStudioRequest): ResponseEntity<StudioResponse>
}
