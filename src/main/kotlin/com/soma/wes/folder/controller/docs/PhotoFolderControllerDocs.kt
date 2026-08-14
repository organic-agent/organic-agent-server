package com.soma.wes.folder.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.dto.request.AddPhotosRequest
import com.soma.wes.folder.dto.request.CreatePhotoFolderRequest
import com.soma.wes.folder.dto.request.MovePhotosRequest
import com.soma.wes.folder.dto.request.RenamePhotoFolderRequest
import com.soma.wes.folder.dto.response.PhotoFolderDetailResponse
import com.soma.wes.folder.dto.response.PhotoFolderResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Folder]", description = "부모폴더 아래에서 사진을 실제로 담는 자식폴더 API")
interface PhotoFolderControllerDocs {

    @Operation(
        summary = "자식폴더 생성",
        description = """
            부모폴더 아래 자식폴더를 만든다. photoIds를 비워 보내면 빈 폴더가 만들어진다 —
            드래그로 채워 넣는 흐름이 빈 폴더에서 시작한다.

            같은 부모 아래 어딘가에 이미 든 사진이 섞여 있으면 전체가 409로 거절된다.

            담당 작가와 초대받은 부부가 쓴다. 부부는 갤러리가 열려 있고 선택 마감 기한 안일 때만 가능하고,
            작가에게는 그 제약이 없다 — 마감은 고객이 고르는 기한이지 작가의 작업 기한이 아니다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "400", description = "이 갤러리의 사진이 아니거나 개수 상한을 넘거나 이름이 올바르지 않음", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더", content = []),
        ApiResponse(responseCode = "409", description = "같은 부모 아래에 이미 든 사진이 섞여 있음", content = []),
    )
    fun create(
        loginUser: LoginUser,
        galleryId: Long,
        groupId: Long,
        request: CreatePhotoFolderRequest,
    ): ResponseEntity<PhotoFolderDetailResponse>

    @Operation(
        summary = "자식폴더 조회",
        description = """
            폴더에 든 사진 전부를 서명된 조회 URL과 함께 돌려준다. 순서는 갤러리 노출 순서를 따른다.

            폴더에 담은 뒤 사진이 삭제됐다면 그 사진은 목록에서 자연히 빠진다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더 또는 폴더", content = []),
    )
    fun get(
        loginUser: LoginUser,
        galleryId: Long,
        groupId: Long,
        folderId: Long,
    ): ResponseEntity<PhotoFolderDetailResponse>

    @Operation(summary = "자식폴더 이름 변경")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(responseCode = "400", description = "이름이 비었거나 100자를 넘음", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더 또는 폴더", content = []),
    )
    fun rename(
        loginUser: LoginUser,
        galleryId: Long,
        groupId: Long,
        folderId: Long,
        request: RenamePhotoFolderRequest,
    ): ResponseEntity<PhotoFolderResponse>

    @Operation(
        summary = "자식폴더 삭제",
        description = "폴더와 그 안의 항목만 지운다. 사진 자체는 갤러리에 그대로 남는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더 또는 폴더", content = []),
    )
    fun delete(loginUser: LoginUser, galleryId: Long, groupId: Long, folderId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "자식폴더에 사진 추가",
        description = "같은 부모 아래 어딘가에 이미 든 사진이 섞여 있으면 전체가 409로 거절된다 — " +
            "그 사진은 다른 자식폴더에 있으므로, 옮기려면 이동 API를 쓴다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "추가 성공"),
        ApiResponse(responseCode = "400", description = "이 갤러리의 사진이 아니거나 개수 상한을 넘음", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더 또는 폴더", content = []),
        ApiResponse(responseCode = "409", description = "같은 부모 아래에 이미 든 사진이 섞여 있음", content = []),
    )
    fun addPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        groupId: Long,
        folderId: Long,
        request: AddPhotosRequest,
    ): ResponseEntity<PhotoFolderDetailResponse>

    @Operation(
        summary = "자식폴더에서 사진 제거",
        description = "폴더에서만 빠진다. 사진 자체는 갤러리에 그대로 남는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거 성공"),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더/폴더이거나 폴더에 없는 사진", content = []),
    )
    fun removePhoto(
        loginUser: LoginUser,
        galleryId: Long,
        groupId: Long,
        folderId: Long,
        photoId: Long,
    ): ResponseEntity<Unit>

    @Operation(
        summary = "사진을 다른 자식폴더로 이동",
        description = """
            사진을 같은 부모의 다른 자식폴더로 옮긴다. 드래그 앤 드롭이 이 API를 부른다.

            옮길 사진이 전부 출발지 폴더에 들어 있어야 하고, 도착지는 같은 부모 아래여야 한다 —
            다른 부모의 자식폴더 id는 404가 된다. 응답은 사진이 도착한 폴더의 상세다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "이동 성공"),
        ApiResponse(responseCode = "400", description = "옮길 사진이 없음", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 부모폴더/폴더이거나 출발지에 없는 사진", content = []),
    )
    fun movePhotos(
        loginUser: LoginUser,
        galleryId: Long,
        groupId: Long,
        folderId: Long,
        request: MovePhotosRequest,
    ): ResponseEntity<PhotoFolderDetailResponse>
}
