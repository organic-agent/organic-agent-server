package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.dto.request.WritePhotoMemoRequest
import com.soma.wes.photo.dto.response.PhotoMemoResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Photo Memo]", description = "사진 메모 API")
interface PhotoMemoControllerDocs {

    @Operation(
        summary = "메모 적기",
        description = """
            사진 한 장의 메모를 적는다. 같은 사진에 다시 보내면 덮어쓴다(PUT인 이유다).

            **메모는 사진당 하나이고 사람마다 나뉘지 않는다.** 개인 갤러리 소유자와 파트너가 같은 한 칸을 함께 고치고,
            마지막에 쓴 사람이 updatedBy에 남는다. 동시에 고치면 나중 저장이 이긴다.

            지금은 **개인 갤러리만** 쓴다 — 초대 갤러리에는 403이다. 작가에게 가지 않으며 보정 요청과는 별개다.
            셀렉 중에도 보정 확인 중에도 적을 수 있고, 보관된 갤러리에만 막는다. 본문은 2000자까지다.

            적은 메모는 사진 목록·상세 응답의 `memo`로 함께 온다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "적기 성공(새로 적었든 덮어썼든 200이다)"),
        ApiResponse(responseCode = "400", description = "빈 메모이거나 2000자 초과(PHOTO_400_9)", content = []),
        ApiResponse(responseCode = "403", description = "개인 갤러리 소유자·파트너가 아니거나 보관된 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "이 갤러리의 사진이 아님(PHOTO_404_1)", content = []),
    )
    fun write(
        loginUser: LoginUser,
        galleryId: Long,
        photoId: Long,
        request: WritePhotoMemoRequest,
    ): ResponseEntity<PhotoMemoResponse>

    @Operation(
        summary = "메모 지우기",
        description = "메모를 없앤다. 적은 적 없는 사진에도 204다. 다만 사진 자체가 이 갤러리의 것이 아니면 404다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "지우기 성공"),
        ApiResponse(responseCode = "403", description = "개인 갤러리 소유자·파트너가 아니거나 보관된 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "이 갤러리의 사진이 아님(PHOTO_404_1)", content = []),
    )
    fun clear(loginUser: LoginUser, galleryId: Long, photoId: Long): ResponseEntity<Unit>
}
