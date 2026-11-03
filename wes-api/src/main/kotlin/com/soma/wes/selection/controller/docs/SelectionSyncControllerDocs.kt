package com.soma.wes.selection.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.selection.dto.response.SelectionSyncResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Selection Sync]", description = "부부 선택·별점 변경 확인 API")
interface SelectionSyncControllerDocs {
    @Operation(
        summary = "선택·별점 변경 번호 조회",
        description = """
            부부 참여자가 화면을 보고 있는 동안 3초 간격으로 조회한다. 작가·게스트는 접근할 수 없다.
            selectionRevision이 달라지면 기존 선택 앨범 GET을, ratingRevision이 달라지면
            GET /api/v1/galleries/{galleryId}/photo-ratings를 호출한다. 변경이 없으면 목록을 다시 읽지 않는다.
            exclusionRevision이 달라지면 일반 사진·제외 사진 목록, 폴더, 공유 정보를 다시 읽는다.
            담기·빼기·재담기·제출·철회와 별점 변경·삭제, 사진의 휴지통 이동·복원도 감지한다.

            처음 진입하거나 재연결할 때는 이 번호를 먼저 읽고 선택·별점 전체 목록을 읽는다.
            목록을 성공적으로 적용한 뒤에만 먼저 읽었던 번호를 기준값으로 저장한다.
            다음 조회에서 번호가 다르면 해당 목록을 다시 읽는다. 목록 조회 뒤 읽은 번호로 기준값을 바꾸면
            그 사이 발생한 변경을 놓칠 수 있다. 실패 시 기존 기준값을 유지하고 다음 주기에 재시도한다.
            같은 화면의 요청은 겹치지 않게 직렬로 처리하고, 재연결 이전의 늦은 응답은 버린다.
            별점 목록은 전체 교체하며 응답에 없는 사진의 점수·작업자·시각을 지운다.
            번호는 갤러리별 비교값이다. 다른 갤러리와 비교하거나 변경 건수·시각으로 해석하지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "선택·별점 변경 번호"),
        ApiResponse(responseCode = "403", description = "부부 참여자가 아님", content = []),
        ApiResponse(responseCode = "404", description = "갤러리 없음", content = []),
    )
    fun get(loginUser: LoginUser, galleryId: Long): ResponseEntity<SelectionSyncResponse>
}
