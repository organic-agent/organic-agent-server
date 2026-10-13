package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.DeletePhotosRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.request.ReissueUploadUrlsRequest
import com.soma.wes.photo.dto.response.IssueUploadUrlsResponse
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoDetailResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.dto.response.PhotoSummaryResponse
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
    name = "[Photo]",
    description = "원본 사진 업로드·조회 API. 목록과 상세 조회는 초대받은 예비 부부에게도 열려 있고, "
        + "올리고 통보하고 집계를 보는 것은 담당 작가 전용이다.",
)
interface PhotoControllerDocs {

    @Operation(
        summary = "업로드 URL 일괄 발급",
        description = """
            업로드 1단계. 파일 목록마다 사진 행을 PENDING으로 만들고 S3 PUT용 서명 URL을 돌려준다.
            이미지 바이트는 이 서버를 거치지 않는다 — 프론트가 받은 URL로 S3에 직접 올린다.

            리사이즈를 끝낸 배치 단위로 부른다. 발급 요청에 파일마다 Content-Type·바이트 수·CRC32C(base64)를 적고, PUT 할 때
            같은 Content-Type·Content-Length 헤더와 x-amz-checksum-crc32c 헤더에 같은 값을 보내야 한다. 셋 다 서명에
            포함되어 있어서 하나라도 빠지거나 다르면 S3가 403(SignatureDoesNotMatch)으로 거절하고, 체크섬이 실제 바이트와
            다르면 400(BadDigest)으로 거절한다. 다른 x-amz-* 헤더는 서명에 없으니 붙이지 않는다.
            크기 상한을 넘거나 체크섬 형식이 틀린 파일은 발급 단계에서 400이다.

            업로드가 끝나면 완료 통보(POST /complete)를 보낸다. 통보가 없어도 서버가 발급 1분 뒤부터 S3를 직접
            확인해 올라온 사진을 UPLOADED로 옮기지만, 통보가 빠르다. 24시간이 지나도 올라오지 않은 사진은 휴지통으로 간다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "400",
            description = "개수 초과 또는 지원하지 않는 형식",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "한 번에 너무 많이 요청함",
                            value = """{"code": "PHOTO_400_1", "message": "한 번에 처리할 수 있는 사진 수를 넘었습니다."}""",
                        ),
                        ExampleObject(
                            name = "임베딩이 읽을 수 없는 형식",
                            value = """{"code": "PHOTO_400_2", "message": "지원하지 않는 이미지 형식입니다."}""",
                        ),
                        ExampleObject(
                            name = "크기 상한 초과",
                            value = """{"code": "PHOTO_400_7", "message": "업로드할 사진 크기가 허용 범위를 벗어났습니다."}""",
                        ),
                        ExampleObject(
                            name = "체크섬 형식 오류",
                            value = """{"code": "PHOTO_400_8", "message": "업로드할 사진의 CRC32C 체크섬 형식이 올바르지 않습니다."}""",
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
    )
    fun issueUploadUrls(
        loginUser: LoginUser,
        galleryId: Long,
        request: IssueUploadUrlsRequest,
    ): ResponseEntity<IssueUploadUrlsResponse>

    @Operation(
        summary = "업로드 URL 재발급",
        description = """
            끊긴 업로드의 재개. 탭을 다시 연 프론트가 자기가 기억하는 PENDING 사진 id로 새 PUT URL을 받는다.
            사진 행을 새로 만들지 않으므로 같은 사진이 두 번 생기지 않는다.

            새 서명이므로 처음 발급과 똑같이 바이트 수와 CRC32C(base64)를 적고, PUT 헤더도 처음과 같은 규칙으로 보낸다.
            이미 올라온(UPLOADED) 사진이 하나라도 섞여 있으면 전부 거절한다 — 새 URL로 원본이 덮이는 일을 막는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재발급 성공"),
        ApiResponse(
            responseCode = "400",
            description = "크기 상한 초과 또는 체크섬 형식 오류",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "크기 상한 초과",
                            value = """{"code": "PHOTO_400_7", "message": "업로드할 사진 크기가 허용 범위를 벗어났습니다."}""",
                        ),
                        ExampleObject(
                            name = "체크섬 형식 오류",
                            value = """{"code": "PHOTO_400_8", "message": "업로드할 사진의 CRC32C 체크섬 형식이 올바르지 않습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리에 없는 사진 id가 섞여 있음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "다른 갤러리의 사진",
                            value = """{"code": "PHOTO_404_1", "message": "존재하지 않는 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 올라온 사진",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "업로드 끝난 사진",
                            value = """{"code": "PHOTO_409_1", "message": "이미 업로드가 끝난 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun reissueUploadUrls(
        loginUser: LoginUser,
        galleryId: Long,
        request: ReissueUploadUrlsRequest,
    ): ResponseEntity<IssueUploadUrlsResponse>

    @Operation(
        summary = "업로드 완료 통보",
        description = """
            업로드 2단계. S3 PUT을 마친 사진들을 PENDING에서 UPLOADED로 옮긴다.
            S3 업로드는 프론트가 직접 하므로 서버는 끝난 사실을 알 방법이 없다.

            여러 번 보내도 안전하다. 이미 UPLOADED인 사진은 그대로다. 임베딩·점수 진행은 사진 상태가 아니라
            집계(GET /summary)와 분석 잡 응답으로 본다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "통보 성공"),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리에 없는 사진 id가 섞여 있음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "다른 갤러리의 사진",
                            value = """{"code": "PHOTO_404_1", "message": "존재하지 않는 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun completeUpload(
        loginUser: LoginUser,
        galleryId: Long,
        request: CompleteUploadRequest,
    ): ResponseEntity<PhotoCountResponse>

    @Operation(
        summary = "사진 휴지통 이동",
        description = """
            사진들을 휴지통으로 보낸다. 담당 작가만 할 수 있다. 한 장을 지워도 배치로 보낸다.

            휴지통의 사진은 목록·폴더·선택 앨범·협업 화면 어디에도 보이지 않는다.
            갤러리별 휴지통(GET /photos/trash)에서 복원하거나 즉시 삭제할 수 있고, 보관 기간이
            지나면 원본과 함께 자동으로 물리 삭제된다.

            전부-아니면-거부다. 이미 휴지통에 있거나 이 갤러리에 없는 id가 섞여 있으면
            한 장도 옮기지 않고 404를 돌려준다 — 화면이 낡았다는 뜻이므로 다시 읽어야 한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "휴지통 이동 성공. 옮긴 사진 수를 돌려준다"),
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
            description = "이 갤러리에 없거나 이미 휴지통에 있는 사진 id가 섞여 있음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "다른 갤러리의 사진",
                            value = """{"code": "PHOTO_404_1", "message": "존재하지 않는 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun moveToTrash(
        loginUser: LoginUser,
        galleryId: Long,
        request: DeletePhotosRequest,
    ): ResponseEntity<PhotoCountResponse>

    @Operation(
        summary = "사진 목록 조회",
        description = """
            갤러리의 사진 전체를 그리드로 그릴 때 부른다. 담당 작가와 초대받은 예비 부부가 함께 쓴다 —
            부부에게는 이것이 전체를 훑는 화면이고, 비슷한 사진 묶음(폴더)은 그다음이다.

            마감된 뒤에도, 선택이 끝난 뒤에도 열린다. 보는 동작이라 고르는 기한과 무관하다.
            다만 아직 열리지 않은(DRAFT) 갤러리는 부부에게 보이지 않는다.

            사진마다 서명된 조회 URL(viewUrl)이 붙어 오므로 그대로 <img src>에 넣으면 된다.
            버킷이 비공개라 storageKey만으로는 이미지를 띄울 수 없다.

            viewUrl은 응답의 viewUrlTtlSeconds 동안만 살아 있다. 그 시간이 지나기 전에
            목록을 다시 부르면 새 URL이 온다. 아직 올라오지 않은(PENDING) 사진은 null이다.

            previewReady가 true면 viewUrl은 브라우저가 그릴 수 있는 파생 JPEG를 가리킨다.
            false면 원본이라 형식에 따라(아이폰 HEIC 등) 그려지지 않을 수 있다. 파생본은
            임베딩이 끝나야 생기므로, 그전까지는 '미리보기 준비 중'으로 안내하면 된다.

            사진마다 매겨진 별점(score, 1~5)이 함께 온다. 아무도 매기지 않았으면 null이다.
            minScore를 주면 그 점수 이상만 온다 — 별점이 없는 사진은 이때 빠진다.

            `page`와 `size`는 범위를 벗어나도 400이 아니라 깎아서 처리한다 — 음수 페이지는
            첫 페이지로, 상한을 넘는 크기는 상한으로 맞춘다. 응답의 `page`·`size`가 실제로
            적용된 값이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "이 갤러리의 작가도 초대받은 멤버도 아니거나, 부부가 아직 열리지 않은(DRAFT) 갤러리를 부름",
            content = [],
        ),
        ApiResponse(
            responseCode = "400",
            description = "minScore가 1~5 밖임. page·size는 400을 내지 않는다",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "범위 밖 minScore",
                            value = """{"code": "PHOTO_400_4", "message": "별점은 1점에서 5점 사이여야 합니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun list(
        loginUser: LoginUser,
        galleryId: Long,
        status: PhotoStatus?,
        minScore: Int?,
        page: Int,
        size: Int,
    ): ResponseEntity<PhotoPageResponse>

    @Operation(
        summary = "사진 상세 조회",
        description = """
            사진 한 장을 크게 볼 때 부른다. 목록과 달리 조회 URL이 둘이다.

            viewUrl은 파생 JPEG(브라우저가 확실히 그리지만 긴 변이 줄어 있다)를, originalUrl은
            원본(원래 크기지만 아이폰 HEIC면 그려지지 않는다)을 가리킨다. 화면에는 viewUrl로
            그리고 확대·다운로드에 originalUrl을 쓰면 된다. previewReady가 false면 아직 파생본이
            없어 둘이 같은 객체를 가리킨다 — 수명만 다르다.

            originalUrl은 viewUrl보다 오래 산다(originalUrlTtlSeconds). 상세는 한 장을 오래
            열어두는 화면이라 목록과 같은 수명으로 서명하면 보는 도중에 만료된다.

            metadata는 촬영 정보(EXIF)다. 임베딩 Lambda가 원본을 디코딩할 때 함께 읽으므로,
            임베딩 전(PENDING·UPLOADED)이거나 원본에 촬영 정보가 없으면 null이다.

            담당 작가와 초대받은 예비 부부가 볼 수 있다. 부부는 갤러리가 열려 있고 선택 마감
            전인 동안에만 열린다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "403",
            description = "이 갤러리의 작가도 초대받은 멤버도 아니거나, 부부가 마감 뒤에 부름",
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
                            name = "선택 마감이 지남",
                            value = """{"code": "GALLERY_403_4", "message": "선택 기한이 지났습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "이 갤러리에 없는 사진 id",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "다른 갤러리의 사진",
                            value = """{"code": "PHOTO_404_1", "message": "존재하지 않는 사진입니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun get(loginUser: LoginUser, galleryId: Long, photoId: Long): ResponseEntity<PhotoDetailResponse>

    @Operation(
        summary = "업로드·분석 진행 집계",
        description = """
            임베딩·점수·분류는 비동기라 응답을 기다릴 수 없다. 진행은 uploaded → embedded → scored → categorized 가
            차오르는 것으로 본다. failed 는 결정적으로 실패해 AI 대상에서 빠진 사진이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
    )
    fun summary(loginUser: LoginUser, galleryId: Long): ResponseEntity<PhotoSummaryResponse>
}
