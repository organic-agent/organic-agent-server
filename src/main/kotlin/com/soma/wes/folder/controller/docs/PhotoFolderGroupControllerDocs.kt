package com.soma.wes.folder.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.dto.request.CreateFolderGroupRequest
import com.soma.wes.folder.dto.request.RenameFolderGroupRequest
import com.soma.wes.folder.dto.response.PhotoFolderGroupResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Folder Group]", description = "자식폴더들을 품는 부모폴더 API")
interface PhotoFolderGroupControllerDocs {

    @Operation(
        summary = "부모폴더 생성 (클러스터 고정)",
        description = """
            부모폴더를 만든다. 클러스터링 결과를 고정할 때는 folders에 묶음별 자식폴더를 함께 실어
            한 번에 만들고, 빈 부모만 만들 때는 folders를 비워 보낸다.

            고정 시점의 사진 목록이 그대로 저장된다. 나중에 임계값을 바꾸거나 사진이 더 올라와도
            폴더는 달라지지 않는다 — 폴더는 클러스터를 가리키는 포인터가 아니라 확정한 목록이다.

            같은 부모 아래 자식들 간에는 사진이 중복될 수 없다. folders의 묶음끼리 사진이 겹치면
            전체가 409로 거절된다. 서로 다른 부모끼리는 같은 사진을 얼마든지 담을 수 있다.

            담당 작가와 초대받은 부부가 쓴다. 부부는 갤러리가 열려 있고 선택 마감 기한 안일 때만 가능하고,
            작가에게는 그 제약이 없다 — 마감은 고객이 고르는 기한이지 작가의 작업 기한이 아니다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "400", description = "이 갤러리의 사진이 아니거나 개수 상한을 넘거나 이름이 올바르지 않음", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "409", description = "묶음 간에 같은 사진이 겹침", content = []),
    )
    fun create(
        loginUser: LoginUser,
        galleryId: Long,
        request: CreateFolderGroupRequest,
    ): ResponseEntity<PhotoFolderGroupResponse>

    @Operation(
        summary = "부모폴더 목록",
        description = "부모마다 자식폴더 요약(대표 사진과 개수)이 함께 온다. 좌측 폴더 메뉴가 이 응답 하나로 그려진다.\n"
            + "최근에 만든 부모가 먼저, 자식은 만든 순서대로다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
    )
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<PhotoFolderGroupResponse>>

    @Operation(summary = "부모폴더 조회", description = "부모 하나와 그 아래 자식폴더 요약들을 돌려준다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더", content = []),
    )
    fun get(loginUser: LoginUser, galleryId: Long, groupId: Long): ResponseEntity<PhotoFolderGroupResponse>

    @Operation(summary = "부모폴더 이름 변경")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(responseCode = "400", description = "이름이 비었거나 100자를 넘음", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더", content = []),
    )
    fun rename(
        loginUser: LoginUser,
        galleryId: Long,
        groupId: Long,
        request: RenameFolderGroupRequest,
    ): ResponseEntity<PhotoFolderGroupResponse>

    @Operation(
        summary = "부모폴더 삭제",
        description = "자식폴더와 항목까지 함께 지운다. 사진 자체는 갤러리에 그대로 남는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더", content = []),
    )
    fun delete(loginUser: LoginUser, galleryId: Long, groupId: Long): ResponseEntity<Unit>
}
