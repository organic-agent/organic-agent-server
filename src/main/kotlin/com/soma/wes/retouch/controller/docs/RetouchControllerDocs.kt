package com.soma.wes.retouch.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.retouch.dto.request.RefineRetouchRequest
import com.soma.wes.retouch.dto.response.RefineRetouchResponse
import com.soma.wes.retouch.dto.request.SubmitRetouchRequestsRequest
import com.soma.wes.retouch.dto.request.MatchRetouchResultsRequest
import com.soma.wes.retouch.dto.response.MatchRetouchResultsResponse
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.CompleteResultsRequest
import com.soma.wes.retouch.dto.request.IssueResultUploadUrlsRequest
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.dto.response.IssueResultUploadUrlsResponse
import com.soma.wes.retouch.dto.response.RetouchOverviewResponse
import com.soma.wes.retouch.dto.response.RetouchPhotoResponse
import com.soma.wes.retouch.dto.response.RetouchRoundDetailResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Retouch]", description = "셀렉 확정 전에 부부가 회차 단위로 보정을 요청하는 API")
interface RetouchControllerDocs {

    @Operation(
        summary = "보정사진 페이지 조회",
        description = """
            회차 목록과 진행 중인 회차의 요청 전부, 계약 횟수(maxRetouchRoundCount)와 남은
            횟수(remainingRoundCount)가 함께 온다.

            갤러리를 볼 수 있는 사람이면 누구나 조회한다 — 작가는 요청을 봐야 결과를 올릴 수
            있고, 부부는 마감 뒤에도 자기가 요청한 것과 결과를 확인할 수 있어야 한다.

            currentRound는 아직 끝나지 않은 회차다. DRAFTING이면 담고 빼고 요청을 적는 중이고,
            REQUESTED면 작가의 응답을 기다리는 중이다. 모든 회차가 끝났거나 아직 아무것도 담지
            않았다면 null이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 멤버인데 아직 열리지 않은 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 갤러리", content = []),
    )
    fun get(loginUser: LoginUser, galleryId: Long): ResponseEntity<RetouchOverviewResponse>

    @Operation(
        summary = "보정사진 담기",
        description = """
            진행 중인 초안(DRAFTING) 회차에 원본 사진을 담는다. 회차가 없으면 이 호출이 만든다.

            스튜디오 갤러리의 부부는 이 경로 대신 셀렉 제출이나 `/rounds/{n}/requests` 로 요청을 한 번에 보낸다.
            이 경로가 필요한 곳은 작가가 없는 **개인 갤러리**다 — 요청서 CSV 를 내보내기 전에 항목을 만들어 둬야 한다.

            이번 회차에 이미 담긴 사진이 섞이면 한 장도 담기지 않는다(409). 이전 회차의 사진은 다시 담을 수 있고,
            이전 회차가 작가 응답을 기다리는 중이면 거절한다(409).
        """,
    )
    fun addPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        request: AddRetouchPhotosRequest,
    ): ResponseEntity<RetouchOverviewResponse>

    @Operation(
        summary = "보정 요청 작성",
        description = """
            초안(DRAFTING) 회차에 담긴 사진 한 장의 요청문과 포인트를 덮어쓴다. 제출된 회차에는 404다 —
            작가가 보고 있는 요청이 도중에 바뀌지 않게 한다.

            요청문은 2000자, 포인트는 100개까지다. 포인트 좌표는 0~1 로 정규화한 값이라 표시 크기가 달라도 같은 곳을 가리킨다.
        """,
    )
    fun updatePhoto(
        loginUser: LoginUser,
        galleryId: Long,
        photoId: Long,
        request: UpdateRetouchPhotoRequest,
    ): ResponseEntity<RetouchPhotoResponse>

    @Operation(
        summary = "보정 회차 상세 조회",
        description = """
            회차 하나의 요청 전부를 항목별 원본·주석·결과 URL과 함께 준다 — 전/후 비교 화면이
            이 응답 하나로 그려진다. 스튜디오 클라이언트에게 resultUrl은 작가의 보내기 전까지 숨긴다.
            작가는 아직 제출하지 않은 초안의 사진과 클라이언트의 별점을 볼 수 없다.

            갤러리를 볼 수 있는 사람이면 누구나 조회한다. 끝난 회차도 조회된다 — 부부는 마감
            뒤에도 회차별 결과를 다시 볼 수 있어야 한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 멤버인데 아직 열리지 않은 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 갤러리 또는 회차(RETOUCH_404_2)", content = []),
    )
    fun getRound(loginUser: LoginUser, galleryId: Long, roundNo: Int): ResponseEntity<RetouchRoundDetailResponse>

    @Operation(
        summary = "보정 결과 업로드 URL 일괄 발급",
        description = """
            제출된(REQUESTED) 회차의 항목들에 결과 파일을 올릴 서명 URL을 발급한다. 담당 작가만
            할 수 있다. 원본·주석 업로드와 같은 방식으로 파일 바이트는 서버를 지나지 않고,
            발급은 아무 행도 만들지 않는다 — 업로드를 마친 뒤 결과 확정 API가 항목에 기록한다.

            응답은 요청한 항목 순서대로의 photoId ↔ resultKey ↔ uploadUrl 매핑이다. PUT 할 때
            발급 요청의 Content-Type을 그대로 보내야 서명이 맞는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "400",
            description = "빈 목록(RETOUCH_400_4), 개수 상한 초과(RETOUCH_400_3), " +
                "지원하지 않는 형식(RETOUCH_400_9)",
            content = [],
        ),
        ApiResponse(responseCode = "403", description = "담당 작가가 아님", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 회차(RETOUCH_404_2), 회차에 없는 사진(RETOUCH_404_1)", content = []),
        ApiResponse(responseCode = "409", description = "작가의 차례가 아닌 회차(RETOUCH_409_3)", content = []),
    )
    fun issueResultUploadUrls(
        loginUser: LoginUser,
        galleryId: Long,
        roundNo: Int,
        request: IssueResultUploadUrlsRequest,
    ): ResponseEntity<IssueResultUploadUrlsResponse>

    @Operation(
        summary = "보정 결과 업로드 확정",
        description = """
            S3 PUT을 마친 결과들을 항목에 기록한다. 담당 작가만, 제출된(REQUESTED) 회차에만
            할 수 있다. resultKey는 발급 API가 돌려준 값을 그대로 보낸다 — 이 회차의 결과
            경로가 아닌 key는 400으로 거절된다.

            회차가 끝나기 전에는 같은 항목에 다시 확정하면 새 key로 덮어쓴다. 회차에 없는
            사진이 하나라도 섞이면 통째로 거절된다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "확정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "이 회차의 결과 key가 아님(RETOUCH_400_10), 지원하지 않는 형식(RETOUCH_400_9), " +
                "빈 목록(RETOUCH_400_4), 개수 상한 초과(RETOUCH_400_3)",
            content = [],
        ),
        ApiResponse(responseCode = "403", description = "담당 작가가 아님", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 회차(RETOUCH_404_2), 회차에 없는 사진(RETOUCH_404_1)", content = []),
        ApiResponse(responseCode = "409", description = "작가의 차례가 아닌 회차(RETOUCH_409_3)", content = []),
    )
    fun completeResults(
        loginUser: LoginUser,
        galleryId: Long,
        roundNo: Int,
        request: CompleteResultsRequest,
    ): ResponseEntity<RetouchRoundDetailResponse>

    @Operation(
        summary = "보정 회차 완료",
        description = """
            결과를 다 올린 회차를 끝낸다(REQUESTED → COMPLETED). 담당 작가만 할 수 있고,
            요청 전부에 결과가 있어야 한다 — 일부만 응답한 채 끝내면 부부는 어떤 사진이
            누락됐는지 알 수 없다.

            끝난 회차부터 부부가 다음 회차를 시작할 수 있다. 완료는 되돌릴 수 없다 —
            빠뜨린 결과는 다음 회차의 재보정 흐름으로 받는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "완료 성공"),
        ApiResponse(responseCode = "400", description = "결과가 없는 항목이 남음(RETOUCH_400_11)", content = []),
        ApiResponse(responseCode = "403", description = "담당 작가가 아님", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 회차(RETOUCH_404_2)", content = []),
        ApiResponse(responseCode = "409", description = "작가의 차례가 아닌 회차(RETOUCH_409_3)", content = []),
    )
    fun completeRound(
        loginUser: LoginUser,
        galleryId: Long,
        roundNo: Int,
    ): ResponseEntity<RetouchOverviewResponse>
    @Operation(summary = "선택 사진의 N차 보정 요청 제출", description = "스튜디오 갤러리 전용이다. requests에는 photoId, requestText, points를 담는다. 선택에 없는 사진은 거절한다.")
    fun submitRequests(loginUser: LoginUser, galleryId: Long, roundNo: Int, request: SubmitRetouchRequestsRequest): ResponseEntity<RetouchOverviewResponse>

    @Operation(summary = "보정 파일명 자동 매칭", description = "확장자를 제외한 파일명을 비교한다. 후보가 여러 개면 photoId=null이며 클라이언트가 후보를 선택해 upload-urls의 photoId로 보낸다.")
    fun matchResults(loginUser: LoginUser, galleryId: Long, roundNo: Int, request: MatchRetouchResultsRequest): ResponseEntity<MatchRetouchResultsResponse>

    @Operation(summary = "클라이언트 보정 확정", description = "스튜디오 갤러리 전용이다. 최신 회차를 작가가 보낸 후에만 확정할 수 있으며 갤러리는 읽기 전용 보관 상태가 된다.")
    fun confirm(loginUser: LoginUser, galleryId: Long): ResponseEntity<RetouchOverviewResponse>

    @Operation(
        summary = "보정 요청 AI 정제안",
        description = "원문은 변경하지 않고 제안만 반환한다. photoId·x·y를 주면 미리보기에 탭 지점을 표시해 함께 보내 대상을 특정하고, " +
            "탭한 곳과 원문이 어긋나면 status=NEEDS_CLARIFICATION으로 되묻는다. 좌표 없이 photoId만 주면 사진 전체 메모로 다룬다. " +
            "photoId가 없으면 텍스트만 보고 말투만 정리한다. " +
            "한글·영문이 하나도 없는 입력은 모델을 부르지 않고 status=NOT_A_REQUEST로 돌려주며, " +
            "LLM 비활성이거나 미리보기를 읽지 못하면 available=false, status=null이다(정제하지 않고 원문을 그대로 둔다).",
    )
    fun refine(loginUser: LoginUser, galleryId: Long, request: RefineRetouchRequest): ResponseEntity<RefineRetouchResponse>
}
