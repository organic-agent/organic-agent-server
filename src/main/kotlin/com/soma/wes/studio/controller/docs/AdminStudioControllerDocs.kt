package com.soma.wes.studio.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.studio.dto.request.ExecuteStudioDeletionRequest
import com.soma.wes.studio.dto.response.StudioDeletionResponse
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * 공개 API 문서에는 노출하지 않는 운영자 전용 경계.
 * 사용자용 `DELETE /api/v1/studios/me`는 의도적으로 존재하지 않는다.
 */
@Hidden
interface AdminStudioControllerDocs {

    fun hardDelete(
        studioId: Long,
        loginUser: LoginUser,
        requestId: UUID,
        request: ExecuteStudioDeletionRequest,
    ): ResponseEntity<StudioDeletionResponse>
}
