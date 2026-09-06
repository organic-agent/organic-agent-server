package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.dto.request.WritePhotoCommentRequest
import com.soma.wes.photo.dto.response.PhotoCommentResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Photo Comment]", description = "부부 내부 사진 댓글 API")
interface PhotoCommentControllerDocs {
    @Operation(
        summary = "내부 사진 댓글 조회",
        description = """
            개인 작업공간 소유자와 갤러리에 초대받은 부부만 읽는다. 스튜디오 관리자와 하객은 접근할 수 없다.
            댓글은 오래된 순(id 오름차순)이며 page는 0부터, size 기본 50/최대 200이다.
            OPEN/CLOSED에서 기한이나 선택 제출 여부와 관계없이 읽으며 DRAFT는 차단한다.
            갤러리/사진이 휴지통에 있거나 업로드 대기 사진이면 404다. 복원 시 기존 댓글도 다시 보인다.
            하객 협업 댓글은 별도 API/저장소이므로 여기에 섞이지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "댓글 한 페이지"),
        ApiResponse(responseCode = "401", description = "계정 JWT 인증 필요", content = []),
        ApiResponse(responseCode = "403", description = "부부/개인 소유자가 아니거나 DRAFT", content = []),
        ApiResponse(responseCode = "404", description = "갤러리 또는 사진을 찾을 수 없음", content = []),
    )
    fun list(
        loginUser: LoginUser,
        galleryId: Long,
        photoId: Long,
        page: Int,
        size: Int,
    ): ResponseEntity<PageResponse<PhotoCommentResponse>>

    @Operation(
        summary = "내부 사진 댓글 작성",
        description = """
            조회 권한이 있는 부부/개인 소유자가 OPEN 갤러리의 선택 기한 안에 작성한다.
            선택 결과를 바꾸지 않으므로 SUBMITTED 상태에서도 작성 가능하다.
            작성자 ID와 닉네임은 로그인 계정에서 정하며 본문은 content만 받는다.
            원문 1~500자이며 앞뒤 공백 제거 후 비어 있으면 거절한다. 댓글 수정은 제공하지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "작성된 댓글"),
        ApiResponse(responseCode = "400", description = "비어 있거나 500자를 넘는 댓글", content = []),
        ApiResponse(responseCode = "401", description = "계정 JWT 인증 필요", content = []),
        ApiResponse(responseCode = "403", description = "접근 권한 없음, 닫힌 갤러리 또는 마감 경과", content = []),
        ApiResponse(responseCode = "404", description = "갤러리 또는 사진을 찾을 수 없음", content = []),
    )
    fun write(
        loginUser: LoginUser,
        galleryId: Long,
        photoId: Long,
        request: WritePhotoCommentRequest,
    ): ResponseEntity<PhotoCommentResponse>

    @Operation(
        summary = "본인 내부 사진 댓글 삭제",
        description = "조회 권한이 있는 작성자만 삭제한다. CLOSED/선택 마감/제출 이후에도 삭제 가능하다. " +
            "다른 작성자는 403, 다른 사진의 댓글이나 이미 삭제한 댓글은 404다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 완료"),
        ApiResponse(responseCode = "401", description = "계정 JWT 인증 필요", content = []),
        ApiResponse(responseCode = "403", description = "접근 권한 없음 또는 다른 작성자의 댓글", content = []),
        ApiResponse(responseCode = "404", description = "갤러리/사진/댓글을 찾을 수 없음", content = []),
    )
    fun delete(
        loginUser: LoginUser,
        galleryId: Long,
        photoId: Long,
        commentId: Long,
    ): ResponseEntity<Unit>
}
