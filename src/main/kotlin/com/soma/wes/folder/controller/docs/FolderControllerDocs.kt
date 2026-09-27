package com.soma.wes.folder.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
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

@Tag(name = "[Folder]", description = "Concept/Detail 카테고리와 비동기 분류 API")
interface FolderControllerDocs {

    @Operation(
        summary = "Concept 카테고리 생성",
        description = """
            사용자가 직접 Concept 폴더를 만든다. 맨 뒤 순서로 붙고 하위 Detail 폴더는 비어 있다.

            담당 작가(개인 갤러리는 두 참여자)는 제출 뒤에도 만들 수 있다. 초대받은 부부는 갤러리가 OPEN이고
            마감 전이며 선택 앨범을 아직 제출하지 않았을 때만 만들 수 있다 — 제출이 끝난 분류를 뒤에서 바꾸지 못하게 하려는 것이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음, 선택 기간이 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "권한 없음",
                            value = """{"code": "GALLERY_403_1", "message": "갤러리에 접근할 권한이 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "선택 중이 아닌 갤러리",
                            value = """{"code": "GALLERY_403_2", "message": "지금은 사진을 고를 수 없는 갤러리입니다."}""",
                        ),
                        ExampleObject(
                            name = "마감 지남",
                            value = """{"code": "GALLERY_403_4", "message": "사진 선택 마감 기한이 지났습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "부부가 선택 앨범을 이미 제출함",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "제출 완료",
                            value = """{"code": "SELECTION_409_1", "message": "이미 제출한 선택 앨범입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun createConcept(
        loginUser: LoginUser,
        galleryId: Long,
        request: CreateConceptFolderRequest,
    ): ResponseEntity<ConceptFolderResponse>

    @Operation(
        summary = "Concept/Detail 카테고리 조회",
        description = "폐기된 photo-clusters API 대신 Concept와 하위 Detail 카테고리 구조를 반환한다. " +
            "담당 작가와 부부 모두 조회할 수 있다(부부에게 DRAFT 갤러리는 보이지 않는다). " +
            "각 Detail의 photoIds가 배정된 사진이고, 어느 Detail에도 없는 사진은 미분류다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "담당 작가도 멤버도 아니거나, 부부에게 아직 공개되지 않은 갤러리",
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
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<ConceptFolderResponse>>

    @Operation(
        summary = "AI 분석 결과로 폴더 세트 물질화",
        description = """
            최신 분석의 컨셉 배정으로 컨셉·세부 폴더(폴더 세트)를 물질화한다.
            담당 작가(개인 갤러리는 두 참여자)만 부를 수 있다. 보통은 분석 잡이 DONE 직전에 자동으로 만들므로 수동 재시도용이다.

            멱등이다 — 최신 분석 잡의 폴더 세트가 이미 있으면 새로 만들지 않고 그것을 돌려준다.
            이미 어느 폴더에든 들어 있는 사진(사용자가 옮긴 것 포함)은 다시 배정하지 않는다.
            분석 결과가 아직 없으면 409_2, 새로 배정할 사진이 한 장도 없으면 409_3이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공(이미 있던 세트를 돌려준 경우 포함)"),
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
            responseCode = "409",
            description = "분석이 끝나지 않았거나, 새로 배정할 사진이 없음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "분석 미완료",
                            value = """{"code": "CATEGORY_409_2", "message": "AI 분석이 끝나지 않아 카테고리를 만들 수 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "배정할 사진 없음",
                            value = """{"code": "CATEGORY_409_3", "message": "새로 카테고리화할 사진이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun createFromAnalysis(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<ConceptFolderResponse>>

    @Operation(
        summary = "Detail 카테고리 생성",
        description = "Concept 아래에 빈 Detail 폴더를 맨 뒤 순서로 만든다. 권한 규칙은 Concept 생성과 같다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(
            responseCode = "403",
            description = "권한 없음, 선택 기간이 아님",
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
            description = "이 갤러리의 Concept가 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "Concept 없음",
                            value = """{"code": "CATEGORY_404_1", "message": "컨셉폴더를 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun createDetail(
        loginUser: LoginUser,
        galleryId: Long,
        conceptId: Long,
        request: CreateDetailFolderRequest,
    ): ResponseEntity<DetailFolderResponse>

    @Operation(
        summary = "사진을 Detail 카테고리로 이동",
        description = """
            사진들을 targetDetailFolderId로 옮긴다. null이면 어느 폴더에도 없는 미분류로 뺀다. 권한 규칙은 Concept 생성과 같다.

            사진이 원래 있던 Concept를 벗어나면 그 Concept에서 남긴 협업 반응도 함께 지워진다 — 같은 Concept 안의 이동은 반응을 남긴다.
            하나라도 이 갤러리의 사진이 아니면 아무것도 옮기지 않고 400_1이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "이동 성공"),
        ApiResponse(
            responseCode = "400",
            description = "옮길 사진이 비었거나, 갤러리에 없는 사진이 섞임",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "갤러리에 없는 사진",
                            value = """{"code": "CATEGORY_400_1", "message": "갤러리에 없는 사진이 포함되어 있습니다."}""",
                        ),
                        ExampleObject(
                            name = "빈 목록",
                            value = """{"code": "CATEGORY_400_2", "message": "이동할 사진이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 Detail이 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "Detail 없음",
                            value = """{"code": "CATEGORY_404_2", "message": "세부폴더를 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun movePhotos(loginUser: LoginUser, galleryId: Long, request: MoveFolderPhotosRequest): ResponseEntity<Unit>

    @Operation(
        summary = "Detail 카테고리 삭제",
        description = "Detail 폴더를 지운다. 안에 있던 사진은 지워지지 않고 미분류가 되며, " +
            "그 사진들이 Concept에서 남긴 협업 반응은 함께 지워진다. 권한 규칙은 Concept 생성과 같다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "Concept 또는 그 아래 Detail이 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "Concept 없음",
                            value = """{"code": "CATEGORY_404_1", "message": "컨셉폴더를 찾을 수 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "Detail 없음",
                            value = """{"code": "CATEGORY_404_2", "message": "세부폴더를 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun deleteDetail(loginUser: LoginUser, galleryId: Long, conceptId: Long, detailId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "Concept 카테고리 삭제",
        description = "Concept와 하위 Detail 폴더를 모두 지운다. 안에 있던 사진은 미분류가 되고, " +
            "이 Concept에 남긴 협업 반응은 모두 지워진다. 권한 규칙은 Concept 생성과 같다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리의 Concept가 아님",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "Concept 없음",
                            value = """{"code": "CATEGORY_404_1", "message": "컨셉폴더를 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun deleteConcept(loginUser: LoginUser, galleryId: Long, conceptId: Long): ResponseEntity<Unit>
}
