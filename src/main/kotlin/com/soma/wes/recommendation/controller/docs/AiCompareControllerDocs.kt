package com.soma.wes.recommendation.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.recommendation.dto.request.ComparePhotosRequest
import com.soma.wes.recommendation.dto.response.PairVerdictResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(name = "[AI Compare]", description = "비교샷 AI 판정(두 사진 중 하나를 고르고 근거를 말한다) API (부부 전용)")
interface AiCompareControllerDocs {

    @Operation(
        summary = "비교샷 AI 판정",
        description = """
            두 사진 중 AI가 담을 쪽을 고르고 근거를 말한다. **동기다** — 응답이 곧 판정이고 보통
            5~6초 걸리므로 프론트는 "AI가 보는 중…"을 그린다. 판정은 항상 하나를 고르며, 거의 같을
            때는 confidence=slight("거의 같아요, 굳이 고르면")로 온다.

            AI 응답이 예산(8초)을 넘거나 실패하면, 또는 이 환경에 LLM이 꺼져 있으면 측정된 수치만으로
            판정한 source=template이 온다 — "AI가 못 골랐어요"가 아니라 "기준으로만 골랐어요" 톤으로 보여준다.

            같은 두 장의 재요청은 저장된 판정을 그대로 돌려준다(cached=true, 순서 무관 — (a,b)와
            (b,a)는 같은 판정). 부부 전용이고, 두 사진 모두 AI 분석(FULL)이 끝나 있어야 한다.
            제출된 앨범에서도 부를 수 있다 — 비교는 앨범을 바꾸지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "판정 성공"),
        ApiResponse(responseCode = "400", description = "같은 사진 두 장", content = []),
        ApiResponse(
            responseCode = "403",
            description = "이 갤러리의 부부가 아니거나, 마감·미공개 갤러리",
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
        ApiResponse(responseCode = "404", description = "이 갤러리에 없는 사진", content = []),
        ApiResponse(responseCode = "409", description = "AI 분석이 끝나지 않은 사진", content = []),
    )
    fun compare(
        loginUser: LoginUser,
        galleryId: Long,
        request: ComparePhotosRequest,
    ): ResponseEntity<PairVerdictResponse>
}
