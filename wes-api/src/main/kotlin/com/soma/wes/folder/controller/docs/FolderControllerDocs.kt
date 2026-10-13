package com.soma.wes.folder.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.request.RenameFolderRequest
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.dto.response.MergeDetailFolderResponse
import com.soma.wes.folder.dto.response.UndoDetailFolderMergeResponse
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
        summary = "Concept 카테고리 이름 변경",
        description = """
            Concept 폴더 이름만 바꾼다. 앞뒤 공백은 지우고, 1~100자가 아니면 400_4다. 같은 이름의 Concept가 있어도 막지 않는다.
            권한 규칙은 사진 이동과 같다 — 개인 갤러리 부부와 작가는 보관 전까지, 초대받은 부부는 셀렉 제출 전까지.

            AI 폴더 만들기는 기존 Concept를 이름으로 찾아 새 사진을 합친다. 이름을 바꾼 Concept에는 옛 이름으로 분류된
            새 사진이 들어오지 않고 새 Concept가 생긴다. 바꾼 이름이 AI 때문에 덮이지는 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공. 하위 Detail과 사진 id를 함께 돌려준다"),
        ApiResponse(
            responseCode = "400",
            description = "이름이 비었거나 100자를 넘음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "이름 규칙 위반",
                            value = """{"code": "CATEGORY_400_4", "message": "폴더 이름은 1~100자로 입력해 주세요."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(responseCode = "403", description = "권한 없음, 셀렉 제출 뒤, 보관된 갤러리"),
        ApiResponse(responseCode = "404", description = "이 갤러리의 Concept가 아님"),
    )
    fun renameConcept(
        loginUser: LoginUser,
        galleryId: Long,
        conceptId: Long,
        request: RenameFolderRequest,
    ): ResponseEntity<ConceptFolderResponse>

    @Operation(
        summary = "Detail 카테고리 이름 변경",
        description = "Detail 폴더 이름만 바꾼다. 이름 규칙과 권한은 Concept 이름 변경과 같다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공. 담긴 사진 id를 함께 돌려준다"),
        ApiResponse(responseCode = "400", description = "이름이 비었거나 100자를 넘음(CATEGORY_400_4)"),
        ApiResponse(responseCode = "403", description = "권한 없음, 셀렉 제출 뒤, 보관된 갤러리"),
        ApiResponse(responseCode = "404", description = "이 갤러리 · Concept의 Detail이 아님"),
    )
    fun renameDetail(
        loginUser: LoginUser,
        galleryId: Long,
        conceptId: Long,
        detailId: Long,
        request: RenameFolderRequest,
    ): ResponseEntity<DetailFolderResponse>

    @Operation(
        summary = "사진을 Detail 카테고리로 이동",
        description = """
            사진들을 targetDetailFolderId로 옮긴다. null이면 어느 폴더에도 없는 미분류로 뺀다. 권한 규칙은 Concept 생성과 같다.

            공유폴더는 Concept · Detail과 따로 살아서, 사진을 옮겨도 공유폴더의 사진과 하객 반응은 바뀌지 않는다.
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
        summary = "Detail 카테고리 합치기",
        description = """
            detailId 폴더의 사진을 모두 targetDetailFolderId 폴더로 옮기고, 비게 된 detailId 폴더를 목록에서 뺀다.
            잘게 나뉜 AI 폴더나 겹치는 폴더를 끌어다 놓아 한 번에 정리하는 용도다. 권한 규칙은 Concept 생성과 같다.

            대상은 이 갤러리의 Detail이면 다른 Concept 아래여도 된다. 공유폴더의 사진과 하객 반응은 바뀌지 않는다(사진 이동과 같은 규칙).
            합쳐진 사진은 사용자 배정이 되어 AI 폴더를 다시 만들어도 원래 폴더로 돌아가지 않는다.

            응답은 되돌리기용 mergeId와 합친 뒤의 대상 Detail(target)이다. mergeId로 3분 안에 "합치기 되돌리기"를 부를 수 있다.
            같은 폴더끼리 합치려 하면 400_3이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "합치기 성공 — mergeId와 합친 뒤의 대상 Detail"),
        ApiResponse(
            responseCode = "400",
            description = "원본과 대상이 같은 폴더",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "같은 폴더",
                            value = """{"code": "CATEGORY_400_3", "message": "같은 세부폴더끼리는 합칠 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
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
            description = "Concept·그 아래 원본 Detail이 아니거나, 대상이 이 갤러리의 Detail이 아님",
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
    fun mergeDetail(
        loginUser: LoginUser,
        galleryId: Long,
        conceptId: Long,
        detailId: Long,
        request: MergeDetailFolderRequest,
    ): ResponseEntity<MergeDetailFolderResponse>

    @Operation(
        summary = "Detail 카테고리 합치기 되돌리기",
        description = """
            합치기 응답의 mergeId로 합치기를 되돌린다. 웹의 "실행 취소"용이다. 권한 규칙은 Concept 생성과 같다.

            사라졌던 원본 Detail이 원래 id · 순서 · 출처(AI/USER)로 돌아오고, 옮겼던 사진이 옮기기 전 배정 그대로 원본으로 돌아간다.
            응답은 되살아난 원본(source)과 사진이 빠진 대상(target)이다.

            합친 뒤 3분 안에 한 번만 된다. 이미 되돌렸으면 409_4, 3분이 지났으면 409_5다.
            그 사이 옮긴 사진이 한 장이라도 다른 곳으로 옮겨졌거나 원본의 Concept · 대상 Detail이 지워졌으면 409_6이다 — 되돌리면 그 변경을 덮어쓰기 때문이다.
            원본 Detail이 지워졌으면(Concept 삭제 포함) 합치기 기록도 함께 지워져 404_3이다.
            3분이 지난 합치기는 매시 정리 작업이 기록까지 지우므로, 그 뒤에는 409_5 대신 404_3이 온다.

        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "되돌리기 성공 — 되살아난 원본과 대상 Detail"),
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
            description = "이 갤러리의 합치기가 아니거나, 원본 Detail이 지워짐, 3분이 지나 정리됨",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "합치기 없음",
                            value = """{"code": "CATEGORY_404_3", "message": "합치기 기록을 찾을 수 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 되돌림, 3분 지남, 그 사이 사진이나 폴더가 바뀜, 부부가 선택 앨범을 이미 제출함",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "이미 되돌림",
                            value = """{"code": "CATEGORY_409_4", "message": "이미 되돌린 합치기입니다."}""",
                        ),
                        ExampleObject(
                            name = "시간 지남",
                            value = """{"code": "CATEGORY_409_5", "message": "되돌릴 수 있는 시간이 지났습니다."}""",
                        ),
                        ExampleObject(
                            name = "그 사이 바뀜",
                            value = """{"code": "CATEGORY_409_6", "message": "합친 뒤 폴더나 사진이 바뀌어 되돌릴 수 없습니다."}""",
                        ),
                        ExampleObject(
                            name = "제출 완료",
                            value = """{"code": "SELECTION_409_1", "message": "이미 제출한 선택 앨범입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun undoMerge(loginUser: LoginUser, galleryId: Long, mergeId: Long): ResponseEntity<UndoDetailFolderMergeResponse>

    @Operation(
        summary = "Detail 카테고리 삭제",
        description = "Detail 폴더를 지운다. 안에 있던 사진은 지워지지 않고 미분류가 되며, " +
            "공유폴더의 사진과 하객 반응은 바뀌지 않는다. 권한 규칙은 Concept 생성과 같다.",
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
            "공유폴더의 사진과 하객 반응은 바뀌지 않는다. 권한 규칙은 Concept 생성과 같다.",
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
