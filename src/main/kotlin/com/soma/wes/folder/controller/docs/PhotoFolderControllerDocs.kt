package com.soma.wes.folder.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.dto.request.AddPhotosRequest
import com.soma.wes.folder.dto.request.CreatePhotoFolderRequest
import com.soma.wes.folder.dto.request.RenamePhotoFolderRequest
import com.soma.wes.folder.dto.response.PhotoFolderDetailResponse
import com.soma.wes.folder.dto.response.PhotoFolderResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "사진 폴더", description = "예비 부부가 확정한 사진 묶음")
interface PhotoFolderControllerDocs {

    @Operation(
        summary = "폴더 생성",
        description = """
            클러스터 결과에 이름을 붙여 폴더로 저장한다. photoIds는 클러스터 응답에서 그대로 옮겨오면 된다.

            저장 시점의 사진 목록이 그대로 고정된다. 나중에 임계값을 바꾸거나 사진이 더 올라와도
            이 폴더는 달라지지 않는다 — 폴더는 클러스터를 가리키는 포인터가 아니라 확정한 목록이다.

            폴더 관련 API는 전부 초대받은 부부만 쓸 수 있다. 갤러리가 열려 있고 선택 마감 기한 안이어야 한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "400", description = "이 갤러리의 사진이 아니거나 개수 상한을 넘음", content = []),
        ApiResponse(responseCode = "403", description = "멤버가 아니거나 마감/미공개 갤러리", content = []),
    )
    fun create(
        loginUser: LoginUser,
        galleryId: Long,
        request: CreatePhotoFolderRequest,
    ): ResponseEntity<PhotoFolderDetailResponse>

    @Operation(
        summary = "폴더 목록",
        description = "사진 없이 이름과 개수만 온다. 최근에 만든 것이 먼저다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "멤버가 아니거나 마감/미공개 갤러리", content = []),
    )
    fun list(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<PhotoFolderResponse>>

    @Operation(
        summary = "폴더 조회",
        description = """
            폴더에 든 사진 전부를 서명된 조회 URL과 함께 돌려준다. 순서는 갤러리 노출 순서를 따른다.

            폴더에 담은 뒤 사진이 삭제됐다면 그 사진은 목록에서 자연히 빠진다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "멤버가 아니거나 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 폴더", content = []),
    )
    fun get(loginUser: LoginUser, galleryId: Long, folderId: Long): ResponseEntity<PhotoFolderDetailResponse>

    @Operation(summary = "폴더 이름 변경")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(responseCode = "400", description = "이름이 비었거나 100자를 넘음", content = []),
        ApiResponse(responseCode = "403", description = "멤버가 아니거나 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 폴더", content = []),
    )
    fun rename(
        loginUser: LoginUser,
        galleryId: Long,
        folderId: Long,
        request: RenamePhotoFolderRequest,
    ): ResponseEntity<PhotoFolderResponse>

    @Operation(
        summary = "폴더 삭제",
        description = "폴더와 그 안의 항목만 지운다. 사진 자체는 갤러리에 그대로 남는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "멤버가 아니거나 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 폴더", content = []),
    )
    fun delete(loginUser: LoginUser, galleryId: Long, folderId: Long): ResponseEntity<Unit>

    @Operation(
        summary = "폴더에 사진 추가",
        description = "이미 들어 있는 사진은 조용히 건너뛴다. 같은 요청을 두 번 보내도 결과는 같다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "추가 성공"),
        ApiResponse(responseCode = "400", description = "이 갤러리의 사진이 아니거나 개수 상한을 넘음", content = []),
        ApiResponse(responseCode = "403", description = "멤버가 아니거나 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 폴더", content = []),
    )
    fun addPhotos(
        loginUser: LoginUser,
        galleryId: Long,
        folderId: Long,
        request: AddPhotosRequest,
    ): ResponseEntity<PhotoFolderDetailResponse>

    @Operation(
        summary = "폴더에서 사진 제거",
        description = "폴더에서만 빠진다. 사진 자체는 갤러리에 그대로 남는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거 성공"),
        ApiResponse(responseCode = "403", description = "멤버가 아니거나 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 폴더이거나 폴더에 없는 사진", content = []),
    )
    fun removePhoto(
        loginUser: LoginUser,
        galleryId: Long,
        folderId: Long,
        photoId: Long,
    ): ResponseEntity<Unit>
}
