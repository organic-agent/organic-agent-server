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

@Tag(name = "[AI Analysis]", description = "갤러리 AI 분석(미리보기·임베딩 → 점수 → 그룹·이름) 잡 API (담당 작가 전용)")
interface AnalysisControllerDocs {

    @Operation(
        summary = "갤러리 AI 분석 요청",
        description = """
            갤러리 전수 분석을 시작한다. 단계 셋이 순서대로 돈다 — EMBED(미리보기·DINOv3 벡터·EXIF) →
            SCORE(CLIP 벡터·미학·기술 점수·피사체) → CATEGORIZE(백분위·연사·임베딩 그룹, Bedrock 이름·배정).
            AI 폴더 생성(POST /concept-folders/ai)은 마지막 단계의 결과 위에서 돈다.

            비동기다. 즉시 202로 돌아오고 상태는 GET으로 폴링한다. 서버가 단계마다 Lambda를 부르고 앞 단계가
            끝나면 다음을 이어 부른다. stage·stageStatus로 어느 단계까지 왔는지, progress로 진행률을 본다.

            본문 없이 부르면 FULL이다. 이미 임베딩된 사진은 첫 단계가 건너뛰므로 사진을 더 올린 뒤 다시 눌러도
            안전하다. mode=EMBED는 미리보기·임베딩만(POST /embeddings/run과 같다), mode=NAMING은 이름·배정만
            다시 돌린다 — VLM 호출이 실패했거나 작가 정의 컨셉을 바꾼 경우를 위한 짧은 잡이고, FULL이 DONE인
            적이 없으면 409_4로 거절한다. force=true는 이미 있는 벡터·점수까지 다시 계산한다.

            업로드가 끝난 사진이 한 장도 없으면 409_2다. 모드와 무관하게 진행 중(PENDING·RUNNING)인 잡이
            있으면 새 잡을 만들지 않고 409_1이다. 끝난 뒤 다시 요청하면 갤러리 전체를 새 기준으로 다시 적재한다.
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
            description = "진행 중인 잡이 있거나, 분석할 사진이 없거나, NAMING인데 FULL이 끝난 적 없음",
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
                            name = "업로드된 사진 없음",
                            value = """{"code": "RECOMMENDATION_409_2", "message": "업로드가 끝난 사진이 없어 AI 분석을 시작할 수 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "NAMING인데 FULL이 끝난 적 없음",
                            value = """{"code": "RECOMMENDATION_409_4", "message": "사진별 분석(full)이 끝난 적이 없어 이름 붙이기를 시작할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "503",
            description = "요청한 모드의 단계 중 실행기(Lambda·로컬 스크립트)가 설정되지 않은 것이 있음. 로컬·테스트에는 없는 것이 정상이다.",
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
            가장 최근 분석 잡의 상태. 요청 후 폴링해 DONE이 되면 AI 폴더 생성과 부부 화면의 AI 추천이 열린다.
            stage는 지금 어느 단계인지(EMBED → SCORE → CATEGORIZE), stageStatus는 그 단계가 실행기에 잡혔는지다.
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
