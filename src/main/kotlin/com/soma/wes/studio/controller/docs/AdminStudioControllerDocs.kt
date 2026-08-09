package com.soma.wes.studio.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.studio.dto.request.ExecuteStudioDeletionRequest
import com.soma.wes.studio.dto.response.StudioDeletionResponse
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * 공개 API 문서에는 노출하지 않는 운영자 전용 경계.
 * 사용자용 `DELETE /api/v1/studios/me`는 의도적으로 존재하지 않는다.
 */
@Hidden
interface AdminStudioControllerDocs {

    @Operation(summary = "운영자 승인 스튜디오 hard delete")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 완료 또는 동일 요청의 기존 결과"),
            ApiResponse(
                responseCode = "503",
                description = "운영 선행 조건이 완료되지 않아 hard delete가 비활성임",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ErrorResponse::class),
                        examples = [
                            ExampleObject(
                                name = "hard delete 비활성",
                                value = """{"code": "STUDIO_503_1", "message": "스튜디오 hard delete가 아직 활성화되지 않았습니다."}""",
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )
    fun hardDelete(
        studioId: Long,
        loginUser: LoginUser,
        requestId: UUID,
        request: ExecuteStudioDeletionRequest,
    ): ResponseEntity<StudioDeletionResponse>
}
