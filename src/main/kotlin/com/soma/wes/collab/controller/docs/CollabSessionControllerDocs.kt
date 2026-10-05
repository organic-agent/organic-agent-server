package com.soma.wes.collab.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.dto.request.CollabPhotoIdsRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabParticipantResponse
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
    description = "사진을 직접 담는 공유폴더와 기존 컨셉 연동 링크를 관리하는 API",
)
interface CollabSessionControllerDocs {

    @Operation(
        summary = "공유폴더 만들기",
        description = "초대받은 갤러리 참여자 또는 개인 공간의 소유자·파트너가 이름만으로 빈 공유폴더를 만든다. " +
            "photoIds로 초기 사진을 담거나, scope로 모든 사진·컨셉 폴더들·세부 폴더들·다른 공유폴더들의 사진을 한 번에 담을 수 있다. " +
            "사진은 한 트랜잭션에서 담겨, 실패하면 공유폴더도 만들어지지 않는다. " +
            "공유폴더는 컨셉·세부 폴더와 따로 살아서, 만든 뒤 원본 폴더의 사진이 옮겨지거나 폴더가 지워져도 바뀌지 않는다. " +
            "conceptFolderId는 옛 요청 모양으로, 그 컨셉을 범위로 보낸 것과 같다. 부를 때마다 새 공유폴더를 만든다. " +
            "새 링크는 7일간 유효하고 보관된 갤러리는 변경할 수 없다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "세션 생성 또는 기존 컨셉 세션 재사용 성공"),
        ApiResponse(responseCode = "403", description = "갤러리 참여 권한 없음 또는 갤러리 보관 상태"),
        ApiResponse(responseCode = "400", description = "사진 지정 방식을 둘 이상 보냄, 범위와 폴더 목록이 맞지 않음, 사진 id 수 초과"),
        ApiResponse(responseCode = "404", description = "이 갤러리의 컨셉폴더·공유폴더·사진이 아님"),
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
        summary = "공유폴더 이름·표지 변경",
        description = "갤러리 참여자가 링크와 사진 구성을 유지하고 전달한 이름·표지 항목만 변경한다. name을 생략하면 표지만 바꾸며 빈 이름은 거절한다. 보관 후에는 변경할 수 없다.",
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
        description = "갤러리 참여자가 세션과 반응을 보존하고 현재 링크만 폐기한다. 마감·보관 후에도 링크 정리는 가능하다.",
    )
    @ApiResponses(ApiResponse(responseCode = "204", description = "폐기 성공"))
    fun revoke(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "협업 링크 재발행",
        description = "기존 반응을 보존한 채 이전 토큰을 교체하고 7일간 유효한 링크를 발급한다. 갤러리 참여자만 가능하며 보관 후에는 발급할 수 없다.",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "재발행 성공"))
    fun republish(loginUser: LoginUser, galleryId: Long, sessionId: Long): ResponseEntity<CollabSessionResponse>

    @Operation(summary = "공유폴더에 사진 추가", description = "수동 공유폴더에 같은 갤러리 사진을 한 번에 최대 10,000장까지 추가한다. 이미 담긴 사진은 한 번만 유지한다.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "전체 추가 성공"))
    fun addPhotos(loginUser: LoginUser, galleryId: Long, sessionId: Long, request: CollabPhotoIdsRequest): ResponseEntity<CollabSessionResponse>

    @Operation(summary = "공유폴더에서 사진 제거", description = "JSON photoIds의 사진을 원자적으로 제거하고 이 폴더에 남긴 반응만 정리한다. 사진 원본·분류·다른 폴더는 유지한다. 이미 빠진 사진은 무시한다.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "전체 제거 성공"))
    fun removePhotos(loginUser: LoginUser, galleryId: Long, sessionId: Long, request: CollabPhotoIdsRequest): ResponseEntity<CollabSessionResponse>

    @Operation(
        summary = "공유폴더에 들어온 사람 조회",
        description = "닉네임을 적고 들어온 하객과 반응을 남긴 참여자 계정을 들어온 순서로 돌려준다. " +
            "보기만 하고 닉네임을 적지 않은 사람은 기록이 없어 나오지 않는다. 폐기·만료된 링크도 조회할 수 있다. " +
            "작가에게는 공개하지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리 참여자가 아님"),
        ApiResponse(responseCode = "404", description = "이 갤러리의 세션이 아님"),
    )
    fun listParticipants(
        loginUser: LoginUser,
        galleryId: Long,
        sessionId: Long,
    ): ResponseEntity<List<CollabParticipantResponse>>

    @Operation(
        summary = "공유폴더의 현재 사진과 반응 결과 조회",
        description = "공유폴더에 담긴 사진을 조회한다. 컨셉·세부 폴더에서 사진을 옮겨도 공유폴더와 반응은 바뀌지 않는다. " +
            "휴지통에 있는 사진은 숨기고, 복원하면 다시 보인다.",
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
        description = "갤러리 참여자가 현재 공유폴더에 포함된 사진의 댓글과 작성자 이름을 읽는다. " +
            "최신 댓글부터 페이지로 반환하며 링크 폐기와 갤러리 마감 후에도 조회할 수 있다. " +
            "휴지통 사진과 공유폴더에서 빠진 사진은 조회할 수 없다.",
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
