package com.soma.wes.recommendation.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.recommendation.dto.request.AiRecommendationRequest
import com.soma.wes.recommendation.dto.response.AiRecommendationListResponse
import com.soma.wes.recommendation.dto.response.AiSelectionJobResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(name = "[AI Recommendation]", description = "폴더별 AI 추천(세부 폴더마다 점수 상위 n장) 잡·조회 API")
interface AiRecommendationControllerDocs {

    @Operation(
        summary = "AI 추천 요청",
        description = """
            셀렉의 추천 한 라운드를 큐에 넣는다. AI 워커가 최신(또는 본문이 집은) AI 폴더 세트의
            세부 폴더마다 목표 장수에 비례한 n장(폴더당 최소 1장, 폴더의 절반 이하)을 점수순으로
            고르고, 연사에서는 연사 대표 1장만 낸다. 목표 장수는 갤러리의 계약 장수
            (maxSelectablePhotoCount)에서 온다. 이는 targetCount를 생략한 호출의 동작이다.

            targetCount(1~500)를 주면 이번 잡에서 새로 추천할 장수로 사용한다. 폴더 절반 상한과
            폴더별 최소 1장 대신 실제 후보 수에 비례 배분하므로 합계가 지정 장수를 초과하지 않는다.
            이미 선택한 사진·거절한 사진·폴더 이상치·중복 연사 대표는 제외한다. 후보가 부족하면
            가능한 사진만 반환하며 job.recommendedCount와 shortfallCount로 실제 수와 부족 수를 알린다.
            이 값은 계약 장수를 바꾸지 않으며, 실제 선택에 담을 때의 정원 검증은 그대로다.

            비동기다. 즉시 202로 돌아오고, 추천은 GET으로 폴링한다. 잡이 DONE이 되면 그 라운드의 추천이 모두 보인다.

            부부 전용이다 — 추천은 부부의 선택을 돕는 초안이고 최종 결정은 사람이 한다. 셀렉 행이
            없으면 여기서 만든다. AI 폴더 세트가 없으면 409_5(폴더 생성이 먼저), 진행 중인 추천 잡이
            있으면 409_6, 폴더는 있는데 사진 순위가 아직 다 차지 않았으면 409_8(업로드 뒤 화질 점수를 채우는 중 —
            잠시 뒤 다시), 제출된 앨범이면 409다.

            첫 요청은 DRAFT, 이후 요청은 REFINE이다 — 담은 사진과 거절을 빼고 최신 라운드를 통째로
            다시 계산한다. 모드는 서버가 이력으로 정하므로 본문에서 받지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "추천 요청 접수"),
        ApiResponse(responseCode = "400", description = "1~500 범위 밖 장수", content = []),
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
        ApiResponse(responseCode = "404", description = "본문이 집은 AI 폴더 세트가 이 갤러리에 없음", content = []),
        ApiResponse(responseCode = "409", description = "AI 폴더 세트가 없거나, 사진 순위가 준비 중이거나, 진행 중인 추천 잡이 있거나, 제출된 앨범", content = []),
    )
    fun request(
        loginUser: LoginUser,
        galleryId: Long,
        request: AiRecommendationRequest?,
    ): ResponseEntity<AiSelectionJobResponse>

    @Operation(
        summary = "AI 추천 조회",
        description = """
            사진마다 가장 최근 추천을 돌려준다. 범위 밖 폴더의 이전 추천도 유지되므로 현재 요청의 답변만
            표시하려면 photos[].round == job.round인 항목을 사용한다. folderId를 주면 그 세부 폴더의 추천만 온다 — 폴더 그리드가
            AI 배지를 그리는 경로다. 세트에 안 들어간 사진(미분류)의 추천은 folderId가 null이다.

            응답의 job으로 진행 상태(PENDING/RUNNING/DONE/FAILED)와 기준 세트(folderSetJobId)를
            함께 본다 — 현재 보는 세트와 다르면 "추천을 다시 받으세요"를 띄운다.

            보는 것이라 작가·부부 모두 부를 수 있다. 셀렉이나 추천이 아직 없으면 빈 목록이지 404가
            아니다 — 요청 전 폴링부터 시작해도 오류로 보이지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공. 추천이 없으면 빈 목록"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 갤러리", content = []),
    )
    fun list(
        loginUser: LoginUser,
        galleryId: Long,
        folderId: Long?,
    ): ResponseEntity<AiRecommendationListResponse>
}
