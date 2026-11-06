package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.dto.request.ChangePhotoExclusionsRequest
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Photo Exclusion]", description = "셀렉 휴지통(제외 폴더). 원본 삭제용 휴지통과 별개이며 만료되지 않는다")
interface PhotoExclusionControllerDocs {
    @Operation(
        summary = "제외 사진 목록",
        description = """
            작가와 부부가 조회한다. 선택 확정 이후에도 읽을 수 있고 게스트는 접근할 수 없다.
            사진 순서·id 순이며 기존 PhotoPageResponse 계약(페이지 크기 최대 200)을 따른다.
            excludedAt·excludedBy와 썸네일 URL을 포함한다. 작가에게 별점·메모는 공개하지 않는다.
            일반 사진 목록·폴더·추천·기존 공유에서는 제외 사진이 보이지 않지만 원본·업로드 용량은 유지한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "제외 사진 페이지"),
        ApiResponse(responseCode = "401", description = "로그인 필요", content = []),
        ApiResponse(responseCode = "403", description = "갤러리 열람 권한 없음", content = []),
        ApiResponse(responseCode = "404", description = "갤러리 없음", content = []),
    )
    fun list(loginUser: LoginUser, galleryId: Long, page: Int, size: Int): ResponseEntity<PhotoPageResponse>

    @Operation(
        summary = "사진을 셀렉에서 일괄 제외",
        description = """
            개인·스튜디오 부부 참여자만 선택 중에 실행한다. 작가는 조회만 한다.
            photoIds는 1~500개(설정 상한)이며 중복은 한 장으로 센다. 업로드 완료 사진만 받는다.
            다른 갤러리·실제 삭제 사진이 하나라도 섞이면 404로 전부 거절한다.
            선택 목록에서도 같은 트랜잭션으로 빼며 폴더 배정·공유 연결·별점·메모·파일은 유지한다.
            이미 제외된 사진은 변경하지 않는다. count는 실제로 새로 제외한 장수다.
            확정·내보내기 이후에는 409다. 기존 재선택 절차를 먼저 거친다.
            성공 후 selection-sync의 exclusionRevision을 기준으로 사진·폴더·공유를 갱신한다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "실제 변경 장수. 재시도는 0일 수 있음"),
        ApiResponse(responseCode = "400", description = "빈 목록·상한 초과·업로드 미완료", content = []),
        ApiResponse(responseCode = "401", description = "로그인 필요", content = []),
        ApiResponse(responseCode = "403", description = "부부 참여자가 아니거나 쓰기 기한 종료", content = []),
        ApiResponse(responseCode = "404", description = "사진 또는 갤러리 없음", content = []),
        ApiResponse(responseCode = "409", description = "확정 이후 제외 잠금", content = []),
    )
    fun exclude(loginUser: LoginUser, galleryId: Long, request: ChangePhotoExclusionsRequest): ResponseEntity<PhotoCountResponse>

    @Operation(
        summary = "사진 제외 해제",
        description = """
            제외와 같은 권한·배치·잠금 규칙이다. 원래 폴더와 기존 공유에 다시 표시하지만 선택 목록에는 자동으로 담지 않는다.
            폴더가 이미 삭제됐다면 미분류로 보인다. 별점·메모·공유 반응은 그대로다.
            이미 복원된 사진은 변경하지 않으며 count는 실제 복원 장수다.
            이 DELETE는 파일 삭제가 아니다. 실제 삭제용 /photos/trash API와 혼동하지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "실제 복원 장수"),
        ApiResponse(responseCode = "400", description = "빈 목록·상한 초과·업로드 미완료", content = []),
        ApiResponse(responseCode = "401", description = "로그인 필요", content = []),
        ApiResponse(responseCode = "403", description = "부부 참여자가 아니거나 쓰기 기한 종료", content = []),
        ApiResponse(responseCode = "404", description = "사진 또는 갤러리 없음", content = []),
        ApiResponse(responseCode = "409", description = "확정 이후 복원 잠금", content = []),
    )
    fun restore(loginUser: LoginUser, galleryId: Long, request: ChangePhotoExclusionsRequest): ResponseEntity<PhotoCountResponse>
}
