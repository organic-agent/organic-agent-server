package com.soma.wes.selection.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.dto.response.PhotoSelectionResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Selection]", description = "부부가 최종적으로 고른 사진을 담는 선택 앨범 API")
interface PhotoSelectionControllerDocs {

    @Operation(
        summary = "선택 앨범 조회",
        description = """
            갤러리의 선택 앨범 하나를 연다. 고른 사진 전부와 계약 장수(maxSelectablePhotoCount),
            남은 장수(remainingCount)가 함께 온다.

            갤러리를 볼 수 있는 사람이면 누구나 조회한다 — 작가는 제출 결과를 마감 뒤에도 봐야 하고,
            부부는 자기가 제출한 것을 계속 확인할 수 있어야 한다.

            아직 한 장도 고르지 않았다면 status는 SELECTING이고 photos는 비어 있다.

            보정본으로 담은 항목은 retouchPhotoId와 결과의 서명 URL(resultUrl)을 함께 든다 —
            화면은 resultUrl이 있으면 그것을, 없으면 photo.viewUrl을 그린다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 멤버인데 아직 열리지 않은 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 갤러리", content = []),
    )
    fun get(loginUser: LoginUser, galleryId: Long): ResponseEntity<PhotoSelectionResponse>

    @Operation(
        summary = "선택 앨범에 사진 담기",
        description = """
            초대받은 부부만 담을 수 있다. 작가가 고객 대신 고를 수는 없다.

            이미 담긴 사진이 하나라도 섞여 있으면 409로 거절된다. 부부 둘이 각자의 화면에서
            고르는 물건이라, 겹쳤다는 것은 보고 있는 화면이 낡았다는 뜻이다 — 조용히 건너뛰면
            신부는 자기가 방금 담았다고 생각하고, 나중에 신랑이 그 사진을 빼면 아무도 이유를 모른다.

            계약 장수를 넘길 때도 마찬가지로 **한 장도 담기지 않고** 통째로 거절된다 —
            들어갈 수 있는 만큼만 담으면 어느 사진이 빠졌는지 알 수 없다.

            아직 업로드가 끝나지 않은(PENDING) 사진은 담을 수 없다. 앨범은 작가가 받아 보정에
            들어가는 납품 목록이라 실체가 없는 사진이 섞이면 안 된다.

            보정본은 retouchPhotos로 담는다. 항목은 어느 쪽으로 담아도 원본을 가리키므로
            같은 컷을 원본과 보정본으로 두 번 담을 수 없고, 보정본의 원본이 photoId와 다르거나
            아직 결과가 없으면 400으로 통째로 거절된다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "담기 성공"),
        ApiResponse(
            responseCode = "400",
            description = "계약 장수 초과(SELECTION_400_1), 다른 갤러리의 사진(SELECTION_400_2), " +
                "개수 상한 초과(SELECTION_400_3), 빈 목록(SELECTION_400_4), 업로드 전 사진(SELECTION_400_5), " +
                "잘못된 보정 항목(RETOUCH_400_12·13), 결과 없는 보정 항목(RETOUCH_400_14)",
            content = [],
        ),
        ApiResponse(responseCode = "403", description = "부부가 아니거나, 마감/미공개 갤러리", content = []),
        ApiResponse(
            responseCode = "409",
            description = "이미 제출한 앨범(SELECTION_409_1), 이미 담긴 사진이 섞임(SELECTION_409_3)",
            content = [],
        ),
    )
    fun select(
        loginUser: LoginUser,
        galleryId: Long,
        request: SelectPhotosRequest,
    ): ResponseEntity<PhotoSelectionResponse>

    @Operation(
        summary = "선택 앨범에서 여러 장 빼기",
        description = "앨범에 없는 id가 섞여 있어도 나머지는 빠진다. 여러 장을 골라 빼는 화면에서 "
            + "그중 하나가 이미 빠져 있는 것은 화면이 조금 낡은 것뿐이라 통째로 거절하지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "빼기 성공"),
        ApiResponse(responseCode = "400", description = "빈 목록", content = []),
        ApiResponse(responseCode = "403", description = "부부가 아니거나, 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "409", description = "이미 제출한 앨범(SELECTION_409_1)", content = []),
    )
    fun deselect(
        loginUser: LoginUser,
        galleryId: Long,
        request: DeselectPhotosRequest,
    ): ResponseEntity<PhotoSelectionResponse>

    @Operation(
        summary = "선택 앨범에서 한 장 빼기",
        description = "앨범에 없는 사진이면 404다. 여러 장을 빼는 API와 달리 조용히 넘어가지 않는다 — "
            + "한 장을 지정해 뺐는데 아무 일도 일어나지 않으면 화면만 지운 것이 된다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "빼기 성공"),
        ApiResponse(responseCode = "403", description = "부부가 아니거나, 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "앨범에 없는 사진(SELECTION_404_1)", content = []),
        ApiResponse(responseCode = "409", description = "이미 제출한 앨범(SELECTION_409_1)", content = []),
    )
    fun deselectPhoto(loginUser: LoginUser, galleryId: Long, photoId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "선택 앨범 제출",
        description = """
            고르기를 끝내고 작가에게 넘긴다. 부부만 할 수 있다.

            제출 뒤에는 담기도 빼기도 막힌다(409). 작가가 이 목록을 보고 보정에 들어가므로
            그 뒤에 조용히 바뀌면 어느 쪽이 최종인지 알 수 없어진다. 되돌리는 것은 작가만 한다.

            계약 장수에 못 미쳐도 제출된다 — 50장 계약에 45장만 고르는 일은 실제로 있다.
            응답의 maxSelectablePhotoCount와 selectedCount로 화면이 미리 물어볼 수 있다.
            한 장도 고르지 않았다면 400이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "제출 성공"),
        ApiResponse(responseCode = "400", description = "고른 사진이 없음(SELECTION_400_6)", content = []),
        ApiResponse(responseCode = "403", description = "부부가 아니거나, 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "409", description = "이미 제출한 앨범(SELECTION_409_1)", content = []),
    )
    fun submit(loginUser: LoginUser, galleryId: Long): ResponseEntity<PhotoSelectionResponse>

    @Operation(
        summary = "선택 앨범 제출 되돌리기",
        description = """
            제출을 취소해 부부가 다시 고를 수 있게 한다. **담당 작가만** 할 수 있다 —
            부부가 스스로 되돌릴 수 있으면 제출이라는 잠금이 아무것도 잠그지 않는다.

            이미 보정에 들어갔을 수도 있으므로 되돌릴지는 작가가 판단한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "되돌리기 성공"),
        ApiResponse(responseCode = "403", description = "담당 작가가 아님", content = []),
        ApiResponse(responseCode = "409", description = "제출되지 않은 앨범(SELECTION_409_2)", content = []),
    )
    fun withdraw(loginUser: LoginUser, galleryId: Long): ResponseEntity<PhotoSelectionResponse>
}
