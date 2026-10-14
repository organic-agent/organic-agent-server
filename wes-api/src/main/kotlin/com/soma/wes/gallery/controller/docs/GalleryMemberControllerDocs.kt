package com.soma.wes.gallery.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.dto.response.GalleryMemberResponse
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

@Tag(name = "[Gallery Member]", description = "갤러리 멤버 API")
interface GalleryMemberControllerDocs {

    @Operation(
        summary = "갤러리 멤버 목록",
        description = "담당 작가와 예비 부부 모두 조회할 수 있다. 마감 뒤에도 열린다. " +
            "정원은 2명이라 최대 두 건이 온다. 내보낼 때 쓰는 값은 userId가 아니라 memberId다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가도 멤버도 아님",
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
    )
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<GalleryMemberResponse>>

    @Operation(
        summary = "갤러리 멤버 내보내기",
        description = "담당 작가만 할 수 있다. 링크가 엉뚱한 사람에게 전달돼 정원을 채워버린 경우를 되돌린다. " +
            "부부끼리는 서로를 내보낼 수 없다. " +
            "**링크는 함께 거둬들이지 않는다** — 같은 링크가 아직 유효하면 내보낸 사람이 다시 들어올 수 있으므로, " +
            "유출된 링크라면 초대 링크 재발급까지 해야 한다. " +
            "별점·선택 앨범·폴더는 갤러리에 붙어 있어 함께 사라지지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "내보내기 성공"),
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
            responseCode = "404",
            description = "이 갤러리의 멤버가 아니거나 존재하지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "멤버 없음",
                            value = """{"code": "GALLERY_404_2", "message": "갤러리 멤버가 아닙니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun remove(loginUser: LoginUser, galleryId: Long, memberId: Long): ResponseEntity<Unit>

    @Operation(summary = "갤러리에서 나가기", description = "현재 사용자의 갤러리 멤버십을 해제하고 담당 스튜디오에 알린다.")
    fun leave(loginUser: LoginUser, galleryId: Long): ResponseEntity<Unit>
}
