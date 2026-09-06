package com.soma.wes.collab.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.global.page.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Collab Guest]", description = "게스트 링크. 입장 후 X-Guest-Token으로 본인 반응을 식별한다. 링크는 7일 후 만료된다.")
interface CollabGuestControllerDocs {
    @Operation(summary = "게스트 링크 검증 및 표지", description = "표지 제목과 작성자, 공유된 앨범 정보를 반환한다. 만료와 해제는 각각 다른 410 에러 코드다.")
    @SecurityRequirements
    fun getLanding(collabToken: String): ResponseEntity<CollabLandingResponse>

    @Operation(summary = "게스트 이름 등록", description = "로그인 없이 등록하며 브라우저가 받은 guestToken을 보관한다.")
    @SecurityRequirements
    fun enter(collabToken: String, request: EnterCollabRequest): ResponseEntity<CollabGuestResponse>

    @Operation(summary = "게스트 이름 변경", description = "X-Guest-Token으로 확인된 본인 이름만 바꾼다. 기존 좋아요와 댓글 작성자도 같은 참여자 이름을 표시한다.")
    @SecurityRequirements
    fun renameGuest(collabToken: String, guestToken: String?, request: EnterCollabRequest): ResponseEntity<CollabGuestResponse>

    @Operation(summary = "공유 사진 목록", description = "토큰이 가리키는 폴더만 조회한다. 클라이언트의 별점은 포함하지 않는다.")
    @SecurityRequirements
    fun listPhotos(collabToken: String, loginUser: LoginUser?, guestToken: String?, page: Int, size: Int): ResponseEntity<CollabPhotoPageResponse>

    @Operation(summary = "사진 댓글 목록", description = "다른 게스트의 댓글과 자신의 댓글 여부를 함께 반환한다.")
    @SecurityRequirements
    fun listComments(collabToken: String, photoId: Long, loginUser: LoginUser?, guestToken: String?, page: Int, size: Int): ResponseEntity<PageResponse<CollabCommentResponse>>

    @Operation(summary = "사진 댓글 작성", description = "게스트 이름과 함께 기록한다. 공유된 사진에만 작성할 수 있다.")
    @SecurityRequirements
    fun writeComment(collabToken: String, photoId: Long, loginUser: LoginUser?, guestToken: String?, request: WriteCollabCommentRequest): ResponseEntity<CollabCommentResponse>

    @Operation(summary = "본인 댓글 삭제", description = "게스트는 본인의 댓글만 삭제한다. 클라이언트의 전체 댓글 삭제는 인증된 collab-sessions 경로를 사용한다.")
    @SecurityRequirements
    fun deleteComment(collabToken: String, commentId: Long, loginUser: LoginUser?, guestToken: String?): ResponseEntity<Unit>

    @Operation(summary = "사진 좋아요", description = "이름을 가진 참여자당 한 표로 멱등 저장한다.")
    @SecurityRequirements
    fun like(collabToken: String, photoId: Long, loginUser: LoginUser?, guestToken: String?): ResponseEntity<Unit>

    @Operation(summary = "사진 좋아요 취소")
    @SecurityRequirements
    fun cancelLike(collabToken: String, photoId: Long, loginUser: LoginUser?, guestToken: String?): ResponseEntity<Unit>
}
