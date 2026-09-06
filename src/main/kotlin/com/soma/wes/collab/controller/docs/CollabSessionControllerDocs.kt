package com.soma.wes.collab.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.global.page.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(
    name = "[Collab Session]",
    description = "컨셉 카테고리별 하객 협업 링크와 반응 결과를 관리하는 API",
)
interface CollabSessionControllerDocs {

    @Operation(
        summary = "컨셉 협업 링크 열기",
        description = "갤러리 관리자 또는 초대받은 부부가 컨셉폴더 하나에 링크 하나를 연다. " +
            "초대받은 부부는 갤러리가 열려 있고 마감 전일 때만 발행할 수 있다. 이미 있으면 같은 세션을 " +
            "재사용해 이름을 갱신하고, 폐기된 링크라면 새 토큰으로 다시 발행한다. 사진 목록은 " +
            "컨셉 아래 상세폴더의 현재 배정을 동적으로 반영한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "세션 생성 또는 기존 컨셉 세션 재사용 성공"),
        ApiResponse(responseCode = "403", description = "갤러리 관리 권한 없음 또는 초대 부부의 발행 기간 종료"),
        ApiResponse(responseCode = "404", description = "이 갤러리의 컨셉폴더가 아님"),
    )
    fun open(
        loginUser: LoginUser,
        galleryId: Long,
        request: OpenCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse>

    @Operation(summary = "협업 세션 목록")
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<CollabSessionResponse>>

    @Operation(summary = "협업 세션 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "이 갤러리의 세션이 아님"),
    )
    fun get(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "협업 세션 이름 변경",
        description = "관리자와 초대 부부가 링크 토큰과 컨셉 연결을 유지하고 표시 이름만 바꾼다. 부부도 마감 후 변경할 수 있다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "변경 성공"))
    fun rename(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        request: RenameCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "협업 링크 폐기",
        description = "관리자와 초대 부부가 세션과 반응을 보존하고 현재 링크만 폐기한다. 부부도 마감 후 폐기할 수 있다.",
    )
    @ApiResponses(ApiResponse(responseCode = "204", description = "폐기 성공"))
    fun revoke(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "협업 링크 재발행",
        description = "기존 반응을 보존한 채 이전 토큰을 교체한다. 초대 부부는 갤러리가 열려 있고 마감 전일 때만 가능하다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "재발행 성공"))
    fun republish(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "컨셉의 현재 사진과 반응 결과 조회",
        description = "세션 컨셉 아래 상세폴더에 현재 배정된 사진을 조회한다. 같은 컨셉 안에서 " +
            "상세폴더를 옮기면 반응은 유지되고, 컨셉 밖으로 옮기면 해당 반응은 제거된다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    fun listPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        page: Int,
        size: Int,
    ): ResponseEntity<CollabPhotoPageResponse>

    @Operation(
        summary = "공유 사진의 댓글 결과 조회",
        description = "갤러리 조회 권한으로 현재 세션 컨셉에 속한 사진의 댓글과 작성자 이름을 읽는다. " +
            "최신 댓글부터 페이지로 반환하며 링크 폐기와 갤러리 마감 후에도 조회할 수 있다. " +
            "휴지통 사진과 현재 컨셉에서 빠진 사진은 조회할 수 없다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리 조회 권한 없음"),
        ApiResponse(responseCode = "404", description = "이 갤러리의 세션이나 현재 공유 사진이 아님"),
    )
    fun listPhotoComments(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        photoId: Long,
        page: Int,
        size: Int,
    ): ResponseEntity<PageResponse<CollabCommentResponse>>

    @Operation(summary = "하객 댓글 관리 삭제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "404", description = "이 세션의 댓글이 아님"),
    )
    fun deleteComment(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
        commentId: Long,
    ): ResponseEntity<Unit>
}
