package com.soma.wes.auth.controller.docs

import com.soma.wes.auth.dto.request.ReissueRequest
import com.soma.wes.auth.dto.request.ReviewLoginRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.dto.response.ReissueResponse
import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(name = "[Auth]", description = "토큰 재발급·심사용 링크 로그인 API")
interface AuthControllerDocs {

    @Operation(
        summary = "토큰 재발급",
        description = "refresh token으로 access token과 refresh token을 함께 재발급한다(rotation). " +
            "방금 쓴 refresh token은 그 즉시 무효가 되므로, 응답으로 받은 새 refresh token을 저장해야 한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재발급 성공. 새 access token과 refresh token을 응답한다."),
        ApiResponse(
            responseCode = "400",
            description = "요청 본문이 올바르지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "본문에 refreshToken이 없음",
                            value = """{"code": "GLOBAL_400_2", "message": "요청 본문이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "401",
            description = "재발급할 수 없는 refresh token. 다시 로그인시켜야 한다.",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "만료·위조됐거나 이미 무효가 된 refresh token",
                            // 서명이 유효해도 저장소의 토큰과 다르면(로그아웃·다른 기기에서 재로그인·재사용) 여기에 걸린다.
                            value = """{"code": "AUTH_401_6", "message": "다시 로그인해 주세요."}""",
                        ),
                        ExampleObject(
                            name = "토큰의 사용자가 사라짐(탈퇴 등)",
                            value = """{"code": "AUTH_401_7", "message": "토큰의 사용자를 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements // access token이 만료된 상태로 부르는 API다. Authorization 헤더를 요구하면 안 된다.
    fun reissue(request: ReissueRequest): ResponseEntity<ReissueResponse>

    @Operation(
        summary = "심사용 링크 로그인",
        description = "심사용 링크(`/review#code=…`)의 코드로, 미리 정해 둔 심사용 개인 사용자 계정의 토큰을 발급한다. " +
            "소셜 로그인 응답과 같은 모양이다. 링크는 심사 기간에만 켜지며 여러 심사위원이 같은 계정을 함께 쓴다 — " +
            "새로 로그인하면 앞 사람의 refresh token이 교체되므로, 재발급이 401이면 링크를 다시 열면 된다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그인 성공. access token과 refresh token을 응답한다."),
        ApiResponse(
            responseCode = "404",
            description = "링크가 꺼졌거나 만료됐거나 코드가 틀림(구분하지 않는다)",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "유효하지 않은 심사용 링크",
                            value = """{"code": "AUTH_404_1", "message": "유효하지 않은 심사용 링크입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements
    fun reviewLogin(request: ReviewLoginRequest): ResponseEntity<LoginResponse>

    @Operation(summary = "로그아웃", description = "서버에 저장된 refresh token을 폐기한다. 현재 access token은 만료 시점까지 유효하다.")
    fun logout(loginUser: LoginUser): ResponseEntity<Unit>
}
