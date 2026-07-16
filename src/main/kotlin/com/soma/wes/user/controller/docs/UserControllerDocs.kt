package com.soma.wes.user.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.user.dto.response.UserResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType

@Tag(name = "[User]", description = "사용자 API")
interface UserControllerDocs {

    @Operation(
        summary = "내 정보 조회",
        description = "access token의 주체에 해당하는 사용자 정보를 조회한다. Authorization 헤더에 access token이 필요하다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "401",
            description = "토큰이 없거나, 만료·위조됐거나, 종류가 맞지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "토큰 없음",
                            // 토큰을 낸 적도 없는 요청에 "토큰이 만료됐다"고 답하면 원인을 오해하게 된다.
                            value = """{"code": "AUTHZ_401_1", "message": "인증이 필요합니다."}""",
                        ),
                        ExampleObject(
                            name = "만료된 토큰 — 재발급을 시도할 것",
                            value = """{"code": "AUTH_401_1", "message": "만료된 토큰입니다."}""",
                        ),
                        ExampleObject(
                            name = "위조·손상된 토큰 — 다시 로그인시킬 것",
                            value = """{"code": "AUTH_401_2", "message": "유효하지 않은 토큰입니다."}""",
                        ),
                        ExampleObject(
                            name = "refresh token으로 호출함",
                            value = """{"code": "AUTH_401_5", "message": "토큰의 종류가 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "토큰은 유효하지만 사용자가 없음(탈퇴 등)",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "존재하지 않는 사용자",
                            value = """{"code": "USER_404_1", "message": "존재하지 않는 사용자입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun getMe(loginUser: LoginUser): UserResponse
}
