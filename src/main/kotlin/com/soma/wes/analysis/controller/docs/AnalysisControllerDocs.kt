package com.soma.wes.analysis.controller.docs

import com.soma.wes.analysis.dto.request.AnalysisRequest
import com.soma.wes.analysis.dto.response.AnalysisJobResponse
import com.soma.wes.auth.domain.LoginUser
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

@Tag(name = "[AI Analysis]", description = "갤러리 AI 분석(임베딩·점수 관측 → 그룹·이름 → AI 폴더 생성) 잡 API (담당 작가 전용)")
interface AnalysisControllerDocs {

    @Operation(
        summary = "갤러리 AI 분석 요청",
        description = """
            갤러리의 AI 폴더를 만드는 잡을 시작한다. 본문은 없다. 프론트는 업로드 큐가 비면 자동으로 부르고, 작가가 버튼으로도 부른다.

            비동기다. 즉시 202로 돌아오고 상태는 GET으로 폴링한다. 사진별 임베딩·점수는 잡과 무관하게 서버가 업로드된 사진을
            배치로 밀고 있고(프론트가 죽어도 이어진다), 잡은 점수가 다 차기를 관측해(ANALYZING) 그룹·이름 붙이기(CATEGORIZING)를
            한 번 부른 뒤 AI 폴더를 자동으로 만들고 DONE으로 닫는다. progress가 사진 수 기준 진행이다.

            이미 벡터·점수가 있는 사진은 다시 계산하지 않는다 — 사진을 더 올린 뒤 다시 눌러도 새 사진만 처리된다.
            전량 재계산(모델 교체)은 관리자 재처리(분석 리셋)의 일이다.

            멱등하다. 진행 중(ANALYZING·CATEGORIZING)인 잡이 있으면 새 잡을 만들지 않고 그 잡을 그대로 돌려준다 — 같은 요청을
            두 번 보내거나 재시도해도 잡은 하나다. 가장 최근 잡이 끝났고 분류할 사진이 남아 있지 않으면 그 끝난 잡을 돌려준다.
            업로드가 끝난 사진이 한 장도 없으면 409_2다.

            이 요청이 없어도 서버가 잡을 만든다: 마지막 사진이 올라오고 1분이 지나면 자동으로 분석을 시작하고, 일시적인 실패는
            2분·10분 뒤 두 번까지 스스로 다시 돌린다. 이 요청은 그 1분을 기다리지 않고 바로 시작하는 빠른 길이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "분석 요청 접수"),
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
            responseCode = "409",
            description = "분석할 사진이 없음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "업로드된 사진 없음",
                            value = """{"code": "RECOMMENDATION_409_2", "message": "업로드가 끝난 사진이 없어 AI 분석을 시작할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "503",
            description = "실행기(Lambda·로컬 스크립트)가 설정되지 않음. 로컬·테스트에는 없는 것이 정상이다.",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "설정 없음",
                            value = """{"code": "PHOTO_503_1", "message": "AI 분석 실행이 설정되지 않았습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun request(loginUser: LoginUser, galleryId: Long, request: AnalysisRequest?): ResponseEntity<AnalysisJobResponse>

    @Operation(
        summary = "갤러리 AI 분석 상태",
        description = """
            가장 최근 분석 잡의 상태와 지금 진행. 요청 후 폴링해 DONE이 되면 AI 폴더가 만들어져 있고 부부 화면의 AI 추천이 열린다.
            progress는 사진 수 기준(expected·embedded·scored·categorized·failed)이고 /photos/summary 와 같은 값이다.
            FAILED면 error에 이유가 있고, 다시 요청하면 새 잡이 만들어진다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "분석을 요청한 적이 없음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "요청 이력 없음",
                            value = """{"code": "RECOMMENDATION_404_1", "message": "AI 분석을 요청한 적이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun latest(loginUser: LoginUser, galleryId: Long): ResponseEntity<AnalysisJobResponse>
}
