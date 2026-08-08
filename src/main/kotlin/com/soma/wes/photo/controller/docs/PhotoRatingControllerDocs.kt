package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.response.PhotoRatingResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Photo Rating]", description = "사진 별점 API")
interface PhotoRatingControllerDocs {

    @Operation(
        summary = "별점 매기기",
        description = """
            사진 한 장에 1~5점을 매긴다. 같은 사진에 다시 보내면 덮어쓴다(POST가 아니라 PUT인 이유다).

            **점수는 사진당 하나이고 누가 매겼는지로 나뉘지 않는다.** 신랑·신부·작가가 같은 한 칸을
            나눠 쓰고, 마지막에 매긴 사람이 ratedBy에 남는다. 부부 두 사람은 같이 고르는 한 팀이라
            각자의 점수를 평균 내는 것이 화면에서 의미가 없고, 작가의 추천작도 같은 자리에 표시된다.

            부부는 갤러리가 열려 있고 선택 마감 기한 안일 때만 매길 수 있고, 작가에게는 그 제약이 없다.

            매긴 점수는 사진 목록·상세·클러스터·폴더 응답의 `score`로 함께 온다.
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
}
