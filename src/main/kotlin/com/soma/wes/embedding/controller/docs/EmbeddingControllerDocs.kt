package com.soma.wes.embedding.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.embedding.dto.response.EmbeddingRunResponse
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

@Tag(name = "[Embedding]", description = "사진 임베딩 실행 API (담당 작가 전용)")
interface EmbeddingControllerDocs {

    @Operation(
        summary = "갤러리 임베딩 실행",
        description = """
            업로드가 끝난 갤러리의 임베딩 계산을 시작한다. 비동기라 즉시 202로 돌아오고,
            진행 상황은 GET /photos/summary의 embedded 수로 확인한다.

            여러 번 눌러도 안전하다. 기본값은 아직 임베딩이 없는 사진만 처리하므로, 중간에
            실패한 실행을 다시 부르면 남은 것만 이어서 한다. force=true는 이미 채워진 것까지
            다시 계산한다 — 모델이나 전처리를 바꿔 전량 재계산할 때만 쓴다.

            응답의 targets는 이번 실행이 채우려는 사진 수이며, 계산이 끝났다는 뜻이 아니다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "실행 요청 접수"),
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
            responseCode = "502",
            description = "Lambda 호출 자체가 실패함(권한·스로틀링). 계산 실패가 아니다.",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "호출 실패 — IAM과 함수 존재를 확인할 것",
                            value = """{"code": "PHOTO_502_1", "message": "임베딩 실행을 시작하지 못했습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "503",
            description = "임베딩 함수가 설정되지 않음. 로컬·테스트에는 Lambda가 없는 것이 정상이다.",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "설정 없음",
                            value = """{"code": "PHOTO_503_1", "message": "임베딩 실행이 설정되지 않았습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun run(loginUser: LoginUser, galleryId: Long, force: Boolean): ResponseEntity<EmbeddingRunResponse>
}
