package com.soma.wes.gallery.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.dto.request.ChangeTargetPhotoCountRequest
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.ReopenGalleryRequest
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
            responseCode = "400",
            description = "이미 지난 선택 마감 기한",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "지난 기한",
                            value = """{"code": "GALLERY_400_2", "message": "사진 선택 마감 기한은 현재 시각보다 뒤여야 합니다."}""",
                        ),
                    ],
                ),
            ],
        ),
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
        summary = "Mock 갤러리 생성",
        description = """
            스튜디오를 가진 작가에게 버전이 고정된 샘플 사진과 사전 계산 임베딩을 seed한다.
            요청 본문 없이 호출하면 제목은 '샘플 갤러리', 기한·계약 장수는 제한 없음으로 만든다.
            본문을 보내면 일반 생성 요청과 같은 세 값을 최초 생성에만 사용할 수 있다.
            스튜디오당 하나만 만들며, 반복 요청은 최초 요청의 갤러리를 200으로 그대로 반환한다.
            이 경우 뒤 요청의 제목·기한·계약 장수는 기존 갤러리를 바꾸지 않는다.

            운영 샘플 자산과 manifest가 준비되기 전에는 신규 생성만 503으로 막는다. 이미 만든
            Mock 갤러리는 기능 gate를 다시 꺼도 반복 요청으로 조회할 수 있다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "최초 생성 또는 기존 Mock 갤러리 반환"),
        ApiResponse(
            responseCode = "400",
            description = "빈 제목, 지난 선택 마감 기한 또는 0 이하 계약 장수",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "온보딩을 마치지 않아 스튜디오가 없음",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "Mock 갤러리 기능이 꺼져 있거나 manifest가 준비되지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "샘플 준비 전",
                            value = """{"code": "GALLERY_503_1", "message": "Mock 갤러리가 아직 준비되지 않았습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun createMock(loginUser: LoginUser, request: CreateGalleryRequest?): ResponseEntity<GalleryResponse>

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

    @Operation(
        summary = "계약 장수 변경",
        description = """
            부부가 최종적으로 고를 사진 장수를 정하거나 바꾼다. 담당 작가만 할 수 있다 —
            계약에서 나오는 값이라 부부가 바꿀 수 있으면 안 된다.

            null을 보내면 제한이 없어진다. 이미 고른 장수보다 작은 값도 받는다: 계약이 줄어드는
            일은 실제로 있고, 그때 부부에게 필요한 것은 몇 장이 넘쳤는지 보여주는 화면이다.
            넘친 상태에서 더 담는 것은 선택 앨범 API가 막는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(
            responseCode = "400",
            description = "0 이하의 장수를 보냄",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "0장짜리 계약",
                            value = """{"code": "GALLERY_400_3", "message": "선택 장수는 1 이상이어야 합니다."}""",
                        ),
                    ],
                ),
            ],
        ),
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
        ApiResponse(responseCode = "404", description = "존재하지 않는 갤러리", content = []),
    )
    fun changeTargetPhotoCount(
        loginUser: LoginUser,
        galleryId: Long,
        request: ChangeTargetPhotoCountRequest,
    ): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "갤러리 열기",
        description = "DRAFT 갤러리를 OPEN으로 바꿔 초대된 사람에게 보인다. 담당 작가만 할 수 있다. " +
            "이미 열렸거나 마감된 갤러리에는 400이 나간다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "열기 성공"),
        ApiResponse(
            responseCode = "400",
            description = "DRAFT가 아닌 갤러리를 열려고 함",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "이미 열렸거나 마감된 갤러리",
                            value = """{"code": "GALLERY_400_1", "message": "현재 상태에서는 할 수 없는 동작입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
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
    fun open(loginUser: LoginUser, galleryId: Long): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "갤러리 선택 마감",
        description = "OPEN 갤러리를 CLOSED로 바꾼다. 담당 작가만 할 수 있다. " +
            "마감한 뒤에도 부부는 갤러리와 사진을 계속 볼 수 있고, 고르거나 묶는 것만 막힌다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "마감 성공"),
        ApiResponse(
            responseCode = "400",
            description = "OPEN이 아닌 갤러리를 마감하려고 함",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "아직 열지 않았거나 이미 마감된 갤러리",
                            value = """{"code": "GALLERY_400_1", "message": "현재 상태에서는 할 수 없는 동작입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
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
    fun close(loginUser: LoginUser, galleryId: Long): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "갤러리 재오픈",
        description = "CLOSED 갤러리를 다시 OPEN으로 바꾼다. 담당 작가만 할 수 있다. " +
            "선택 마감 기한을 요청에서 다시 받는다 — 지난 기한을 그대로 두면 열자마자 다시 막히기 때문이다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재오픈 성공"),
        ApiResponse(
            responseCode = "400",
            description = "CLOSED가 아닌 갤러리를 다시 열려고 하거나, 이미 지난 기한을 보냄",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "마감된 적 없는 갤러리",
                            value = """{"code": "GALLERY_400_1", "message": "현재 상태에서는 할 수 없는 동작입니다."}""",
                        ),
                        ExampleObject(
                            name = "지난 기한",
                            value = """{"code": "GALLERY_400_2", "message": "사진 선택 마감 기한은 현재 시각보다 뒤여야 합니다."}""",
                        ),
                    ],
                ),
            ],
        ),
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
    fun reopen(
        loginUser: LoginUser,
        galleryId: Long,
        request: ReopenGalleryRequest,
    ): ResponseEntity<GalleryResponse>
}
