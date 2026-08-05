package com.soma.wes.gallery.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
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

@Tag(name = "[Gallery]", description = "갤러리 API")
interface GalleryControllerDocs {

    @Operation(
        summary = "갤러리 생성",
        description = "스튜디오를 가진 작가만 만들 수 있다. 만들어진 갤러리는 DRAFT이며, 초대된 사람에게는 아직 보이지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(
            responseCode = "404",
            description = "온보딩을 마치지 않아 스튜디오가 없음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "스튜디오 없음 — 온보딩부터 시켜야 한다",
                            value = """{"code": "STUDIO_404_1", "message": "존재하지 않는 스튜디오입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun create(loginUser: LoginUser, request: CreateGalleryRequest): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "내 갤러리 목록",
        description = "작가는 스튜디오의 갤러리 전부를, 예비 부부는 초대받아 들어온 갤러리를 받는다. " +
            "예비 부부에게는 아직 열리지 않은(DRAFT) 갤러리가 보이지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
    )
    fun list(loginUser: LoginUser): ResponseEntity<List<GalleryResponse>>

    @Operation(
        summary = "갤러리 단건 조회",
        description = "담당 작가이거나, 초대를 수락한 멤버여야 한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "멤버도 담당 작가도 아니거나, 아직 열리지 않은 갤러리를 멤버가 조회함",
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
            description = "존재하지 않는 갤러리",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "갤러리 없음",
                            value = """{"code": "GALLERY_404_1", "message": "존재하지 않는 갤러리입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun get(loginUser: LoginUser, galleryId: Long): ResponseEntity<GalleryResponse>
}
