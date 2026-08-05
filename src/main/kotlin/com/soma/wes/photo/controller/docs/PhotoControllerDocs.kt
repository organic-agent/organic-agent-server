package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.response.IssueUploadUrlsResponse
import com.soma.wes.photo.dto.response.PhotoCountResponse
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

@Tag(name = "[Photo]", description = "원본 사진 업로드·조회 API (담당 작가 전용)")
interface PhotoControllerDocs {

    @Operation(
        summary = "업로드 URL 일괄 발급",
        description = """
            업로드 1단계. 파일 목록마다 사진 행을 PENDING으로 만들고 S3 PUT용 서명 URL을 돌려준다.
            이미지 바이트는 이 서버를 거치지 않는다 — 프론트가 받은 URL로 S3에 직접 올린다.

            PUT 할 때 발급 요청에 적은 것과 같은 Content-Type을 보내야 한다. 그 값이 서명에
            포함되어 있어서, 다르면 S3가 SignatureDoesNotMatch로 거절한다.

            업로드가 끝나면 반드시 완료 통보(POST /complete)를 보내야 한다. 통보가 없으면
            사진은 PENDING에 머물고 임베딩 대상이 되지 않는다.
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
        summary = "업로드 완료 통보",
        description = """
            업로드 2단계. S3 PUT을 마친 사진들을 PENDING에서 UPLOADED로 옮긴다.
            S3 업로드는 프론트가 직접 하므로 서버는 끝난 사실을 알 방법이 없다.

            여러 번 보내도 안전하다. 이미 UPLOADED인 사진은 그대로고, 임베딩까지 끝난 사진은
            EMBEDDED를 유지한다.
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
        summary = "사진 목록 조회",
        description = """
            사진마다 서명된 조회 URL(viewUrl)이 붙어 오므로 그대로 <img src>에 넣으면 된다.
            버킷이 비공개라 storageKey만으로는 이미지를 띄울 수 없다.

            viewUrl은 응답의 viewUrlTtlSeconds 동안만 살아 있다. 그 시간이 지나기 전에
            목록을 다시 부르면 새 URL이 온다. 아직 올라오지 않은(PENDING) 사진은 null이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "size가 허용 범위를 벗어남",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "페이지 크기 초과",
                            value = """{"code": "PHOTO_400_1", "message": "한 번에 처리할 수 있는 사진 수를 넘었습니다."}""",
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
        page: Int,
        size: Int,
    ): ResponseEntity<PhotoPageResponse>

    @Operation(
        summary = "사진 상태 집계",
        description = """
            임베딩 실행은 비동기라 응답을 기다릴 수 없다. 진행 상황은 이 집계의 embedded 수가
            늘어나는 것으로 확인한다. total과 embedded가 같아지면 클러스터링을 시작할 수 있다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
    )
    fun summary(loginUser: LoginUser, galleryId: Long): ResponseEntity<PhotoSummaryResponse>
}
