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
import com.soma.wes.retouch.dto.response.IssueAnnotationUploadUrlResponse
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
            마음에 드는 사진을 보정사진 풀에 담아둔다. 초대받은 부부만 담을 수 있다.

            진행 중인 DRAFTING 회차가 없으면 첫 담기 때 자동으로 만들어진다. 단, 이전 회차가
            REQUESTED(작가 응답 대기 중)라면 409다 — 회차는 갤러리당 하나씩만 진행된다.
            계약 횟수를 이미 다 썼다면 새 회차가 열리지 않는다(400).

            이번 회차에 이미 담긴 사진이 하나라도 섞여 있으면 통째로 409로 거절된다 — selection과
            같은 이유로, 겹쳤다는 것은 보고 있는 화면이 낡았다는 뜻이다. 이전 회차에 담았던
            사진은 다시 담을 수 있다(재보정 흐름).

            아직 업로드가 끝나지 않은(PENDING) 사진은 담을 수 없다. 보정을 맡길 사진은 실물이
            있어야 한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "담기 성공"),
        ApiResponse(
            responseCode = "400",
            description = "계약 횟수 소진(RETOUCH_400_1), 다른 갤러리의 사진(RETOUCH_400_2), " +
                "개수 상한 초과(RETOUCH_400_3), 빈 목록(RETOUCH_400_4), 업로드 전 사진(RETOUCH_400_5)",
            content = [],
        ),
        ApiResponse(responseCode = "403", description = "클라이언트가 아니거나, 종료/미공개 갤러리", content = []),
        ApiResponse(
            responseCode = "409",
            description = "이전 회차 진행 중(RETOUCH_409_1), 이미 담긴 사진이 섞임(RETOUCH_409_2)",
            content = [],
        ),
    )
    fun addPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        request: AddRetouchPhotosRequest,
    ): ResponseEntity<RetouchOverviewResponse>

    @Operation(
        summary = "보정사진 빼기",
        description = "DRAFTING 회차에서 한 장을 빼낸다. 회차에 없는 사진이거나 이미 제출된 " +
            "뒤라면 404다 — 한 장을 지정해 뺐는데 아무 일도 일어나지 않으면 화면만 지운 것이 된다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "빼기 성공"),
        ApiResponse(responseCode = "403", description = "클라이언트가 아니거나, 종료/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "요청 목록에 없는 사진(RETOUCH_404_1)", content = []),
    )
    fun removePhoto(loginUser: LoginUser, galleryId: Long, photoId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "사진별 보정 요청 작성",
        description = """
            사진 한 장의 요청 텍스트와 주석 이미지 key를 저장한다. 보낸 값으로 통째로 덮어쓰므로
            일부만 고칠 때도 두 필드를 모두 보내야 한다. null은 지운다는 뜻이다.

            제출 전(DRAFTING)에만 쓸 수 있다. 제출 뒤에는 진행 중인 DRAFTING 회차가 없어 404다 —
            주석이 이미지 방식이라 제출 뒤 부분 수정이 없다.

            annotationKey는 주석 업로드 URL 발급 API가 돌려준 값을 그대로 보낸다. 이 갤러리의
            주석 경로가 아닌 key는 400으로 거절된다. 업로드를 마치지 않은 key를 저장하면 주석이
            깨진 이미지로 보인다 — 업로드 완료 뒤에 저장하라.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "저장 성공"),
        ApiResponse(
            responseCode = "400",
            description = "요청 텍스트가 너무 김(RETOUCH_400_7), 이 갤러리의 주석 key가 아님(RETOUCH_400_8)",
            content = [],
        ),
        ApiResponse(responseCode = "403", description = "클라이언트가 아니거나, 종료/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "요청 목록에 없는 사진(RETOUCH_404_1)", content = []),
    )
    fun updatePhoto(
        loginUser: LoginUser,
        galleryId: Long,
        photoId: Long,
        request: UpdateRetouchPhotoRequest,
    ): ResponseEntity<RetouchPhotoResponse>

    @Operation(
        summary = "주석 이미지 업로드 URL 발급",
        description = """
            프론트가 캔버스로 그린 주석 레이어 PNG를 올릴 서명 URL을 발급한다. 발급은 아무 행도
            만들지 않는다 — 업로드를 마친 뒤 요청 작성 API에 annotationKey를 보내야 사진에 붙는다.

            원본 업로드와 같은 방식이다: 이미지 바이트는 서버를 지나지 않고 브라우저가 S3에
            직접 PUT 한다. Content-Type은 image/png로 보내야 서명이 맞는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(responseCode = "403", description = "클라이언트가 아니거나, 종료/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 갤러리", content = []),
    )
    fun issueAnnotationUploadUrl(loginUser: LoginUser, galleryId: Long): ResponseEntity<IssueAnnotationUploadUrlResponse>

    @Operation(
        summary = "보정 회차 제출",
        description = """
            모아둔 요청들을 한 회차로 작가에게 보낸다. 부부만 할 수 있고, 계약 횟수(보정 N회)
            한 번을 쓴다.

            제출 뒤에는 담기·빼기·요청 작성이 모두 막힌다 — 작가가 이 목록을 보고 보정에
            들어가므로 그 뒤에 조용히 바뀌면 어느 쪽이 최종인지 알 수 없어진다. 다음 회차는
            작가가 이번 회차를 끝내야 시작된다.

            한 장도 담지 않았거나 진행 중인 DRAFTING 회차가 없으면 400이다. 계약 횟수를 넘기는
            제출도 400이다 — 회차를 만든 뒤 계약 횟수가 줄었을 수 있어 제출이 최종 관문이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "제출 성공"),
        ApiResponse(
            responseCode = "400",
            description = "요청할 사진이 없음(RETOUCH_400_6), 계약 횟수 소진(RETOUCH_400_1)",
            content = [],
        ),
        ApiResponse(responseCode = "403", description = "클라이언트가 아니거나, 종료/미공개 갤러리", content = []),
    )
    fun submitRound(loginUser: LoginUser, galleryId: Long): ResponseEntity<RetouchOverviewResponse>

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
    @Operation(summary = "선택 사진의 N차 보정 요청 제출", description = "스튜디오 갤러리 전용이다. requests에는 photoId, requestText, annotationKey, points를 담는다. 선택에 없는 사진은 거절한다.")
    fun submitRequests(loginUser: LoginUser, galleryId: Long, roundNo: Int, request: SubmitRetouchRequestsRequest): ResponseEntity<RetouchOverviewResponse>

    @Operation(summary = "보정 파일명 자동 매칭", description = "확장자를 제외한 파일명을 비교한다. 후보가 여러 개면 photoId=null이며 클라이언트가 후보를 선택해 upload-urls의 photoId로 보낸다.")
    fun matchResults(loginUser: LoginUser, galleryId: Long, roundNo: Int, request: MatchRetouchResultsRequest): ResponseEntity<MatchRetouchResultsResponse>

    @Operation(summary = "클라이언트 보정 확정", description = "스튜디오 갤러리 전용이다. 최신 회차를 작가가 보낸 후에만 확정할 수 있으며 갤러리는 읽기 전용 보관 상태가 된다.")
    fun confirm(loginUser: LoginUser, galleryId: Long): ResponseEntity<RetouchOverviewResponse>

    @Operation(summary = "보정 결과 업로드 URL 발급", description = "개인 갤러리의 1회 요청서에 대한 편의 경로. 개설자와 파트너 모두 업로드한다.")
    fun issuePersonalResultUploadUrls(loginUser: LoginUser, galleryId: Long, request: IssueResultUploadUrlsRequest): ResponseEntity<IssueResultUploadUrlsResponse>

    @Operation(summary = "보정 결과 업로드 완료", description = "개인 갤러리는 별도 보내기 없이 바로 조회할 수 있다. 스튜디오에서는 /rounds/1/send 전까지 결과가 숨겨진다.")
    fun completePersonalResults(loginUser: LoginUser, galleryId: Long, request: CompleteResultsRequest): ResponseEntity<RetouchRoundDetailResponse>
    @Operation(
        summary = "보정 요청 AI 정제안",
        description = "원문은 변경하지 않고 제안만 반환한다. LLM 비활성 환경은 available=false, status=null을 반환한다. " +
            "한글·영문이 하나도 없는 입력은 모델을 부르지 않고 status=NOT_A_REQUEST로 돌려준다.",
    )
    fun refine(loginUser: LoginUser, galleryId: Long, request: RefineRetouchRequest): ResponseEntity<RefineRetouchResponse>
}
