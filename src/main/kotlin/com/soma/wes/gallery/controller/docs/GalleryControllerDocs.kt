package com.soma.wes.gallery.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.dto.request.ChangeMaxRetouchRoundCountRequest
import com.soma.wes.gallery.dto.request.ChangeMaxSelectablePhotoCountRequest
import com.soma.wes.gallery.dto.request.ChangeSelectionDeadlineRequest
import com.soma.wes.gallery.dto.request.ChangeShootTypeRequest
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.RenameGalleryRequest
import com.soma.wes.gallery.dto.request.ReopenGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.domain.GalleryStage
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
            운영자가 시드해 둔 샘플 템플릿 갤러리의 사진(임베딩·촬영 정보·미리보기 포함)을
            복제해, 완전히 일반적인 갤러리 하나를 만든다. 실제 촬영 없이 제품을 눌러보려는
            온보딩 직후의 작가를 위한 것이다.

            요청 본문 없이 호출하면 제목은 '샘플 갤러리', 기한·계약 장수는 제한 없음으로
            만든다. 본문을 보내면 일반 생성 요청과 같은 세 값을 쓴다. 부를 때마다 새 갤러리를
            만든다 — 만들어진 갤러리는 일반 갤러리와 구분되지 않으므로, 버튼을 언제 감출지는
            화면이 정한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공. 응답은 일반 갤러리와 같다"),
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
            responseCode = "502",
            description = "템플릿 객체의 S3 복사 실패. 만들다 만 갤러리는 남지 않으므로 다시 요청하면 된다",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "복사 실패",
                            value = """{"code": "PHOTO_502_3", "message": "샘플 사진 복제를 완료하지 못했습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "503",
            description = "샘플 템플릿 갤러리가 설정되지 않았거나 임베딩까지 끝난 사진이 없음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "샘플 준비 전",
                            value = """{"code": "GALLERY_503_1", "message": "샘플 갤러리가 아직 준비되지 않았습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun createMock(loginUser: LoginUser, request: CreateGalleryRequest): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "내 갤러리 목록",
        description = "작가는 스튜디오의 갤러리 전부를, 예비 부부는 초대받아 들어온 갤러리를 받는다. " +
            "예비 부부에게는 아직 열리지 않은(DRAFT) 갤러리가 보이지 않는다. " +
            "stage를 지정하면 UPLOAD부터 ARCHIVED까지 6단계 중 하나로 필터링한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
    )
    fun list(loginUser: LoginUser, stage: GalleryStage?): ResponseEntity<List<GalleryResponse>>

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
    fun changeMaxSelectablePhotoCount(
        loginUser: LoginUser,
        galleryId: Long,
        request: ChangeMaxSelectablePhotoCountRequest,
    ): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "계약 보정 횟수 변경",
        description = """
            부부가 보정을 요청할 수 있는 회차 수를 정하거나 바꾼다. 담당 작가만 할 수 있다 —
            계약에서 나오는 값이라 부부가 바꿀 수 있으면 안 된다.

            null을 보내면 제한이 없어진다. 이미 쓴 횟수보다 작은 값도 받는다: 계약이 줄어드는
            일은 실제로 있고, 넘긴 상태의 새 요청은 보정 API가 막는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(
            responseCode = "400",
            description = "0 이하의 횟수를 보냄",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "0회짜리 계약",
                            value = """{"code": "GALLERY_400_4", "message": "보정 횟수는 1 이상이어야 합니다."}""",
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
    fun changeMaxRetouchRoundCount(
        loginUser: LoginUser,
        galleryId: Long,
        request: ChangeMaxRetouchRoundCountRequest,
    ): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "촬영 종류 변경",
        description = "갤러리의 촬영 종류(리허설/본식/기타)를 바꾼다. 담당 작가만 할 수 있다. " +
            "AI 폴더의 큰 분류 목록이 이 값으로 갈리므로, 바꾼 뒤 AI 분석(NAMING)을 다시 돌려야 새 목록이 반영된다. " +
            "이미 만들어 둔 AI 폴더는 그대로 남는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
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
    fun changeShootType(
        loginUser: LoginUser,
        galleryId: Long,
        request: ChangeShootTypeRequest,
    ): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "갤러리 이름 변경",
        description = "갤러리 이름을 바꾼다. 담당 작가만 할 수 있다. " +
            "이름은 상태와 무관한 표시 정보라 DRAFT·OPEN·CLOSED 어느 상태에서든 바꿀 수 있다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(
            responseCode = "400",
            description = "빈 이름 또는 100자 초과",
            content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
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
    fun rename(
        loginUser: LoginUser,
        galleryId: Long,
        request: RenameGalleryRequest,
    ): ResponseEntity<GalleryResponse>

    @Operation(
        summary = "선택 마감 기한 변경",
        description = """
            사진 선택 마감 기한을 바꾼다. 담당 작가만 할 수 있다. null을 보내면 기한이 없어진다.

            상태는 건드리지 않는다 — CLOSED 갤러리의 기한도 바꿀 수 있지만 그것만으로 다시
            열리지는 않으며, 다시 여는 것은 재오픈 API의 일이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(
            responseCode = "400",
            description = "이미 지난 기한을 보냄",
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
    fun changeSelectionDeadline(
        loginUser: LoginUser,
        galleryId: Long,
        request: ChangeSelectionDeadlineRequest,
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

    @Operation(
        summary = "갤러리 휴지통 이동",
        description = """
            갤러리를 휴지통으로 보낸다. 담당 작가만 할 수 있다.

            갤러리와 그 안의 사진·폴더·선택 앨범·협업 링크가 모두 보이지 않게 된다.
            휴지통(GET /api/v1/trash/galleries)에서 복원하거나 즉시 삭제할 수 있고,
            보관 기간이 지나면 사진 원본과 함께 자동으로 물리 삭제된다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "휴지통 이동 성공"),
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
            description = "존재하지 않거나 이미 휴지통에 있는 갤러리",
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
    fun moveToTrash(loginUser: LoginUser, galleryId: Long): ResponseEntity<Unit>
}
