package com.soma.wes.auth.controller.docs

import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.dto.response.LoginUrlResponse
import com.soma.wes.global.exception.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity


@Tag(name = "[OAuth]", description = "소셜 로그인 API")
interface OAuthControllerDocs {

    @Operation(
        summary = "로그인 URL 생성",
        description = "provider의 인가 페이지 주소를 만들어 응답한다. 클라이언트는 이 URL로 이동시키기만 하면 된다. " +
            "client-id·redirect-uri·scope를 서버가 쥐고 있으므로 provider 설정이 바뀌어도 클라이언트는 고칠 게 없다.\n\n" +
            "로그인 후 돌아올 주소(redirect_uri)는 요청의 Origin으로 정해진다. " +
            "로컬 프론트에서 부르면 로컬로, 배포 프론트에서 부르면 배포 도메인으로 돌아온다. " +
            "이 문서(Swagger)에서 직접 부르면 서버에 설정된 기본 오리진이 쓰인다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그인 URL 생성 성공"),
        ApiResponse(
            responseCode = "400",
            description = "지원하지 않는 provider",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "지원하지 않는 provider",
                            value = """{"code": "AUTH_400_1", "message": "지원하지 않는 소셜 로그인입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "500",
            description = "서버의 소셜 로그인 설정 누락",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "설정 오류",
                            value = """{"code": "AUTH_500_1", "message": "소셜 로그인 설정이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements // 로그인 전에 부르는 API다. 토큰을 요구하면 안 된다.
    fun loginUrl(
        @Parameter(
            description = "provider 이름",
            schema = Schema(allowableValues = ["google", "naver", "kakao"], example = "kakao"),
        )
        provider: String,
        // 브라우저가 알아서 붙이는 헤더다. 문서에 입력란으로 띄우면 손으로 채우라는 뜻이 되어버린다.
        @Parameter(hidden = true)
        origin: String?,
    ): ResponseEntity<LoginUrlResponse>

    @Operation(
        summary = "소셜 로그인 / 회원가입",
        description = "인가 코드로 provider에서 사용자 정보를 가져와 우리 토큰을 발급한다. " +
            "처음 보는 사용자면 가입시키고, 이미 있으면 로그인시킨다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그인 성공. access token과 refresh token을 응답한다."),
        ApiResponse(
            responseCode = "400",
            description = "지원하지 않는 provider, 유효하지 않은 인가 코드, 잘못된 요청 본문",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "지원하지 않는 provider",
                            value = """{"code": "AUTH_400_1", "message": "지원하지 않는 소셜 로그인입니다."}""",
                        ),
                        ExampleObject(
                            name = "유효하지 않은 인가 코드",
                            value = """{"code": "AUTH_400_2", "message": "유효하지 않은 인가 코드입니다."}""",
                        ),
                        ExampleObject(
                            name = "요청 본문에 code가 없음",
                            value = """{"code": "GLOBAL_400_2", "message": "요청 본문이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "502",
            description = "provider와 통신하지 못했거나 provider의 응답을 해석하지 못함",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "provider 통신 실패",
                            value = """{"code": "AUTH_502_1", "message": "소셜 로그인 서버와 통신하지 못했습니다."}""",
                        ),
                        ExampleObject(
                            name = "provider 응답 해석 실패",
                            value = """{"code": "AUTH_502_2", "message": "소셜 로그인 서버의 응답을 해석하지 못했습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    @SecurityRequirements // 토큰을 발급받는 API다. 토큰이 있을 리 없다.
    fun login(
        @Parameter(
            description = "provider 이름",
            schema = Schema(allowableValues = ["google", "naver", "kakao"], example = "kakao"),
        )
        provider: String,
        request: AuthCodeRequest,
        @Parameter(hidden = true)
        origin: String?,
    ): LoginResponse
}
