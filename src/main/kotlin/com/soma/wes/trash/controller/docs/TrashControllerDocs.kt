package com.soma.wes.trash.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.trash.dto.request.EraseTrashedPhotosRequest
import com.soma.wes.trash.dto.request.RestorePhotosRequest
import com.soma.wes.trash.dto.response.TrashedGalleryResponse
import com.soma.wes.trash.dto.response.TrashedPhotoListResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(
    name = "[Trash]",
    description = "휴지통 API. 지운 사진·갤러리는 보관 기간 동안 여기서 복원하거나 즉시 완전 삭제할 수 있고, "
        + "기간이 지나면 원본과 함께 자동으로 물리 삭제된다. 전부 담당 작가 전용이다.",
)
interface TrashControllerDocs {

    @Operation(
        summary = "휴지통 갤러리 목록",
        description = "내 스튜디오의 휴지통에 있는 갤러리들. 스튜디오가 없는 계정에는 빈 목록을 돌려준다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
    )
    fun listGalleries(loginUser: LoginUser): ResponseEntity<List<TrashedGalleryResponse>>

    @Operation(
        summary = "갤러리 복원",
        description = """
            휴지통의 갤러리를 되살린다. 사진·폴더·앨범·협업 링크가 지우기 전 모습 그대로 돌아온다.
            갤러리보다 먼저 개별 삭제된 사진은 복원 뒤에도 휴지통에 남는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "복원 성공"),
        ApiResponse(
            responseCode = "404",
            description = "내 스튜디오의 휴지통에 없는 갤러리",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "휴지통에 없음",
                            value = """{"code": "TRASH_404_2", "message": "휴지통에 없는 갤러리입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun restoreGallery(loginUser: LoginUser, galleryId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "갤러리 즉시 완전 삭제",
        description = """
            휴지통의 갤러리를 보관 기간을 기다리지 않고 지금 물리 삭제한다. 사진 원본·미리보기와
            폴더·앨범·협업 기록까지 모두 사라지며, 복구할 수 없다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "내 스튜디오의 휴지통에 없는 갤러리",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "아직 유효한 업로드 URL이 있음. URL 수명(30분)이 지나면 다시 시도할 수 있다",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "업로드 URL 유효",
                            value = """{"code": "TRASH_409_1", "message": "아직 유효한 사진 업로드 URL이 있어 지금은 완전히 삭제할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun eraseGallery(loginUser: LoginUser, galleryId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "휴지통 사진 목록",
        description = "이 갤러리의 휴지통에 있는 사진들. 갤러리 자체가 휴지통에 있으면 404다 — 갤러리 휴지통에서 갤러리째 다룬다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가가 아님",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "존재하지 않거나 휴지통에 있는 갤러리",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun listPhotos(loginUser: LoginUser, galleryId: Long): ResponseEntity<TrashedPhotoListResponse>

    @Operation(
        summary = "사진 복원",
        description = """
            휴지통의 사진들을 되살린다. 목록·폴더에 다시 나타난다.

            전부-아니면-거부다. 휴지통에 없는 id가 섞여 있으면 한 장도 되살리지 않고 404를
            돌려준다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "복원 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가가 아님",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 휴지통에 없는 사진 id가 섞여 있음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "휴지통에 없음",
                            value = """{"code": "TRASH_404_1", "message": "휴지통에 없는 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun restorePhotos(
        loginUser: LoginUser,
        galleryId: Long,
        request: RestorePhotosRequest,
    ): ResponseEntity<Unit>

    @Operation(
        summary = "사진 즉시 완전 삭제",
        description = """
            휴지통의 사진들을 보관 기간을 기다리지 않고 지금 물리 삭제한다. 원본·미리보기가
            함께 사라지며, 복구할 수 없다. 전부-아니면-거부다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가가 아님",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 휴지통에 없는 사진 id가 섞여 있음",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "아직 유효한 업로드 URL이 있음. URL 수명(30분)이 지나면 다시 시도할 수 있다",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "업로드 URL 유효",
                            value = """{"code": "TRASH_409_1", "message": "아직 유효한 사진 업로드 URL이 있어 지금은 완전히 삭제할 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun erasePhotos(
        loginUser: LoginUser,
        galleryId: Long,
        request: EraseTrashedPhotosRequest,
    ): ResponseEntity<Unit>
}
