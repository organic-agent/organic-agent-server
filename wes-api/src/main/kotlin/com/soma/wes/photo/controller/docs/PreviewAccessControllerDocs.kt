package com.soma.wes.photo.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.photo.dto.response.PreviewAccessResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(
    name = "[Photo]",
    description = "원본 사진 업로드·조회 API. 목록과 상세 조회는 초대받은 예비 부부에게도 열려 있고, "
        + "올리고 통보하고 집계를 보는 것은 담당 작가 전용이다.",
)
interface PreviewAccessControllerDocs {

    @Operation(
        summary = "미리보기 접근 쿠키 받기",
        description = """
            미리보기가 CDN 고정 주소(만료 없음)로 나갈 때, 그 주소를 열 수 있는 쿠키를 Set-Cookie로 준다.
            쿠키는 이 갤러리의 미리보기 경로에만 맞고(다른 갤러리 사진은 403), HttpOnly · Secure · SameSite=Lax다.

            갤러리를 볼 수 있는 부부·작가만 받는다(목록 조회와 같은 권한). 하객 협업 링크는 대상이 아니다 — 하객 미리보기는 서명 URL이다.
            웹은 갤러리를 열 때 목록과 함께 부르고 둘 다 끝난 뒤 그리드를 그린다(쿠키보다 이미지가 먼저 나가면 첫 화면이 403).
            그 뒤 refreshAfterSeconds마다 다시 부른다 — 목록은 다시 읽지 않는다. 다시 부를 때마다 권한을 확인하므로
            멤버에서 빠지면 쿠키 수명 안에 미리보기를 잃는다.

            쿠키가 실리지 않는 환경(일부 인앱 브라우저)이면 목록·상세를 preview=signed로 다시 읽어 서명 URL을 받는다.
            CDN이 꺼져 있으면 쿠키 없이 expiresAt·refreshAfterSeconds가 null이다.
            이 요청만 credentials: include로 보낸다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "쿠키 발급(CDN이 꺼져 있으면 쿠키 없음)"),
        ApiResponse(
            responseCode = "403",
            description = "갤러리를 볼 권한이 없음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ErrorResponse::class),
                    examples = [
                        ExampleObject(
                            name = "권한 없음",
                            value = """{"code": "GALLERY_403_1", "message": "갤러리에 접근할 권한이 없습니다."}""",
                        ),
                    ],
                ),
            ],
        ),
    )
    fun issue(
        loginUser: LoginUser,
        galleryId: Long,
    ): ResponseEntity<PreviewAccessResponse>
}
