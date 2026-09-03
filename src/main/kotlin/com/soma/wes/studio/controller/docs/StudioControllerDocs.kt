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
        summary = "스튜디오 작업공간 생성",
        description = """
            소셜 로그인을 마친 사용자가 스튜디오 작업공간을 만든다.
            요청 사용자는 새 작업공간의 OWNER가 되며, 기존 개인 작업공간과 다른 스튜디오 소속은 유지된다.

            공개 주소는 앞뒤 공백을 제거하고 Locale.ROOT 기준 소문자로 변환한 canonical 값으로 저장한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공. 요청 사용자는 새 스튜디오 작업공간의 OWNER가 된다."),
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

            `galleryUrl` query parameter는 앞뒤 공백 제거와 Locale.ROOT 소문자 변환을 거치며,
            응답의 `galleryUrl`에는 실제 생성·수정에 쓰이는 canonical 값이 담긴다.

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
        description = "이름과 공개 주소를 바꾼다. 공개 주소는 생성과 같은 규칙으로 정규화하며, 주소를 그대로 두고 이름만 바꾸는 것도 된다.",
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

    @Operation(
        summary = "스튜디오 멤버 탈퇴",
        description = "MEMBER가 해당 스튜디오 소속에서 나간다. OWNER는 스튜디오 삭제 API를 사용해야 한다.",
    )
    fun leave(loginUser: LoginUser, studioId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "내 스튜디오 삭제",
        description = "내가 소유한 단일 스튜디오와 하위 갤러리를 삭제하고 다른 소속 멤버에게 알린다.",
    )
    fun deleteMyStudio(loginUser: LoginUser): ResponseEntity<Unit>
}
