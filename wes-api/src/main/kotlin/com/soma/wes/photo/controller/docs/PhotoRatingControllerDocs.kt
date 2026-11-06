package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.response.PhotoRatingHistoryResponse
import com.soma.wes.photo.dto.response.PhotoRatingResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Photo Rating]", description = "사진 별점 API")
interface PhotoRatingControllerDocs {

    @Operation(
        summary = "갤러리 별점 전체 목록",
        description = """
            부부 참여자만 조회한다. 작가·게스트에게는 공개하지 않는다. 별점 있는 사진만 photoId 순서로 반환한다.
            처음 진입하거나 연결을 복구했을 때 이 목록을 기준으로 별점 상태 전체를 교체한다.
            응답에 없는 사진은 별점이 없는 상태이므로 기존 점수·작성자·시각도 지운다. 휴지통 사진은 제외한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "별점 전체 목록. 없으면 빈 배열"),
        ApiResponse(responseCode = "403", description = "부부 참여자가 아님", content = []),
        ApiResponse(responseCode = "404", description = "갤러리 없음", content = []),
    )
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<PhotoRatingResponse>>

    @Operation(
        summary = "별점 매기기",
        description = """
            사진 한 장에 1~5점을 매긴다. 같은 사진에 다시 보내면 덮어쓴다(POST가 아니라 PUT인 이유다).

            점수는 사진당 하나이며 두 참여자가 공유한다. 마지막에 매긴 사람이 ratedBy에 남는다.
            작가는 매기거나 읽을 수 없다. 초대 고객은 공개된 갤러리의 선택 마감 전,
            개인 갤러리 참여자는 이용 기간 안에 매길 수 있다.

            사진 목록·폴더 응답에는 score·ratedBy·ratedAt으로 함께 온다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "매기기 성공(새로 매겼든 덮어썼든 200이다)"),
        ApiResponse(responseCode = "400", description = "1~5 밖의 점수(PHOTO_400_4)", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "이 갤러리의 사진이 아님(PHOTO_404_1)", content = []),
    )
    fun rate(
        loginUser: LoginUser,
        galleryId: Long,
        photoId: Long,
        request: RatePhotoRequest,
    ): ResponseEntity<PhotoRatingResponse>

    @Operation(
        summary = "별점 지우기",
        description = "별점을 없앤다. 매긴 적 없는 사진에도 204다 — 만들려는 상태(점수 없음)가 이미 그것이라 "
            + "다시 보내도 결과가 같다. 다만 사진 자체가 이 갤러리의 것이 아니면 404다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "지우기 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "이 갤러리의 사진이 아님(PHOTO_404_1)", content = []),
    )
    fun clear(loginUser: LoginUser, galleryId: Long, photoId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "사진 별점 기록",
        description = """
            사진 한 장의 별점이 누가 · 몇 점으로 · 언제 바뀌어 왔는지 최신순으로 최대 50건 돌려준다. score가 null인 줄은 그때 지웠다는 뜻이고,
            ratedBy는 지운 줄에서도 지운 사람이다. 공유 별점 목록과 같이 부부 참여자만 읽는다.

            점수나 매긴 사람이 실제로 바뀐 때만 한 줄이 생긴다 — 같은 사람이 같은 점수를 다시 매기거나 없는 별점을 지우면 생기지 않는다.
            그래서 변경 조회의 ratingRevision이 오를 때만 열려 있는 사진의 기록을 다시 읽으면 된다. 휴지통 사진은 404다.
            이 기능이 생기기 전(2026-11)의 변경과 관리자 편집은 기록에 없다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "최신순 기록. 없으면 빈 배열"),
        ApiResponse(responseCode = "403", description = "부부 참여자가 아님(작가·게스트·외부인)", content = []),
        ApiResponse(responseCode = "404", description = "이 갤러리의 사진이 아니거나 휴지통 사진(PHOTO_404_1)", content = []),
    )
    fun history(loginUser: LoginUser, galleryId: Long, photoId: Long): ResponseEntity<List<PhotoRatingHistoryResponse>>
}
