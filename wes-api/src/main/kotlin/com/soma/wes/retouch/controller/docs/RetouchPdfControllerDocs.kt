package com.soma.wes.retouch.controller.docs

import com.soma.wes.auth.domain.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Retouch]")
interface RetouchPdfControllerDocs {
    @Operation(
        summary = "저장된 보정 요청 PDF 내려받기",
        description = "갤러리 열람 권한으로 해당 회차의 저장된 요청과 핀을 출력한다. " +
            "scope는 all(전체), noResult(결과 없음), memo(메모 있음)이며 기본 all이다. " +
            "작가는 미제출 초안을 받을 수 없다. 이미지와 핀 번호는 긴 요청의 다음 페이지에도 반복된다. " +
            "미저장 웹 초안과 관리자 주석 레이어는 포함하지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "PDF 파일", content = [Content(
            mediaType = "application/pdf", schema = Schema(type = "string", format = "binary"),
        )]),
        ApiResponse(responseCode = "400", description = "잘못된 범위, 빈 대상, PDF 크기 상한 초과", content = []),
        ApiResponse(responseCode = "401", description = "로그인 필요", content = []),
        ApiResponse(responseCode = "403", description = "갤러리 열람 권한 없음", content = []),
        ApiResponse(responseCode = "404", description = "갤러리, 회차 또는 원본 사진 없음", content = []),
        ApiResponse(responseCode = "409", description = "미리보기 준비 전", content = []),
        ApiResponse(responseCode = "429", description = "다른 PDF 생성 중", content = []),
        ApiResponse(responseCode = "503", description = "PDF 생성 실패 또는 시간 상한 초과", content = []),
    )
    fun download(loginUser: LoginUser, galleryId: Long, roundNo: Int, scope: String): ResponseEntity<ByteArray>
}
