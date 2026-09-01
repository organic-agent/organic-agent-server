package com.soma.wes.recommendation.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.recommendation.dto.request.AiAnalysisRequest
import com.soma.wes.recommendation.dto.response.AiAnalysisJobResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(name = "[AI Analysis]", description = "갤러리 AI 분석(임베딩 그룹·피사체·점수·클러스터) 잡 API (담당 작가 전용)")
interface AiAnalysisControllerDocs {

    @Operation(
        summary = "갤러리 AI 분석 요청",
        description = """
            임베딩이 끝난 갤러리의 전수 분석을 큐에 넣는다. 사진마다 임베딩 그룹·피사체·기술·미학
            점수·근접 중복 클러스터가 적재되고, 그룹마다 (큰 분류, 컨셉) 이름이 배정된다 — AI 폴더
            생성(POST /folder-groups/ai)은 이 결과 위에서 돈다.

            비동기다. 즉시 202로 돌아오고 상태는 GET으로 폴링한다. 분석 배치가 큐를 집어가므로
            시작까지 시간이 걸릴 수 있다. 진행률은 GET의 progress로 본다.

            본문 없이 부르면 FULL(사진별 분석 전체)이다. mode=NAMING은 이름·배정만 다시 돌린다 —
            VLM 호출이 실패했거나 작가 정의 컨셉을 바꾼 경우를 위한 짧은 잡이고, FULL이 DONE인 적이
            없으면 409_4로 거절한다.

            FULL은 임베딩 실행(POST /embeddings/run)이 먼저다 — 벡터가 한 장도 없거나(409_2) 아직 안
            끝난 사진이 남아 있으면(409_3) 거절한다. 모드와 무관하게 진행 중(PENDING·RUNNING)인 잡이
            있으면 새 잡을 만들지 않고 409다. 끝난 뒤 다시 요청하면 갤러리 전체를 새 기준으로 다시
            적재한다(사진을 더 올린 뒤에도 마찬가지).
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
            description = "진행 중인 잡이 있거나, 임베딩이 없거나 아직 전부 끝나지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "진행 중인 잡",
                            value = """{"code": "RECOMMENDATION_409_1", "message": "이미 진행 중인 AI 분석이 있습니다."}""",
                        ),
                        ExampleObject(
                            name = "임베딩 없음 — embeddings/run 먼저",
                            value = """{"code": "RECOMMENDATION_409_2", "message": "임베딩이 끝난 사진이 없어 AI 분석을 시작할 수 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "임베딩 미완료 — 남은 사진이 끝날 때까지",
                            value = """{"code": "RECOMMENDATION_409_3", "message": "임베딩이 아직 끝나지 않은 사진이 있어 AI 분석을 시작할 수 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "NAMING인데 FULL이 끝난 적 없음",
                            value = """{"code": "RECOMMENDATION_409_4", "message": "사진별 분석(full)이 끝난 적이 없어 이름 붙이기를 시작할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun request(loginUser: LoginUser, galleryId: Long, request: AiAnalysisRequest?): ResponseEntity<AiAnalysisJobResponse>

    @Operation(
        summary = "갤러리 AI 분석 상태",
        description = """
            가장 최근 분석 잡의 상태. 요청 후 폴링해 DONE이 되면 부부 화면의 AI 추천이 열린다.
            FAILED면 error에 배치가 남긴 이유가 있고, 다시 요청하면 새 잡이 만들어진다.
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
    fun latest(loginUser: LoginUser, galleryId: Long): ResponseEntity<AiAnalysisJobResponse>
}
