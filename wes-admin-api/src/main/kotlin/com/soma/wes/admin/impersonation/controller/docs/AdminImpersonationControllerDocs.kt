package com.soma.wes.admin.impersonation.controller.docs

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.impersonation.dto.request.StartAdminImpersonationRequest
import com.soma.wes.admin.impersonation.dto.response.AdminImpersonationResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import java.util.UUID

@Tag(name = "Admin Impersonation", description = "최고 관리자의 읽기 전용 대리보기. 세션은 관리자 로그인 세션 하나에 묶이고, 활성 대리보기가 있는 동안 그 로그인 세션의 쓰기 요청은 대리보기 종료와 로그아웃을 빼고 모두 403(ADMIN_403_2)이다.")
interface AdminImpersonationControllerDocs {
    @Operation(summary = "대리보기 시작", description = "대상은 USER·STUDIO·GALLERY만 받는다. 그 밖의 종류, 삭제·정지된 대상, 대상에 접근 권한이 없는 viewer는 400(ADMIN_400_8)이다. viewerUserId를 생략하면 대상 작업공간의 OWNER(USER 대상은 본인) 권한으로 본다. 로그인 세션 하나에 활성 대리보기는 하나뿐이다 — 이미 있으면 읽기 전용 필터가 먼저 403(ADMIN_403_2)으로 막고, 동시에 시작해 부딪치면 409(ADMIN_409_13)이다. 세션은 30분 뒤 만료된다. view는 실제 사용자 서비스의 권한 검사를 거친 읽기 결과라 그 사용자에게 막힌 영역은 비워 두며, 서명 URL·저장 키·공유폴더 토큰은 담지 않는다. 요청 IP는 세션 행에 저장하지 않는다.")
    fun start(loginUser: AdminLoginUser, request: StartAdminImpersonationRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminImpersonationResponse>

    @Operation(summary = "현재 대리보기 조회", description = "현재 로그인 세션의 활성 대리보기를 돌려주고, view는 조회할 때마다 다시 읽는다. 만료된 세션은 조회 때 닫히며 활성 세션이 없으면 404(ADMIN_404_5)이다.")
    fun current(loginUser: AdminLoginUser, servletRequest: HttpServletRequest): ResponseEntity<AdminImpersonationResponse>

    @Operation(summary = "현재 대리보기 종료", description = "현재 로그인 세션의 활성 대리보기에 종료 시각을 남긴다. 활성 세션이 없거나 이미 만료됐으면 404(ADMIN_404_5)이다. 대리보기 중에도 쓸 수 있는 쓰기 요청이다.")
    fun endCurrent(loginUser: AdminLoginUser, servletRequest: HttpServletRequest): ResponseEntity<Void>

    @Operation(summary = "대리보기 조회", description = "id로 대리보기를 조회한다. 자기 로그인 세션에서 시작한 활성 세션만 보이고, 다른 로그인 세션의 것이나 만료·종료된 것은 404(ADMIN_404_5)이다.")
    fun view(loginUser: AdminLoginUser, id: UUID, servletRequest: HttpServletRequest): ResponseEntity<AdminImpersonationResponse>

    @Operation(summary = "대리보기 종료", description = "id로 대리보기에 종료 시각을 남긴다. 자기 로그인 세션의 활성 세션만 끝낼 수 있고, 그 밖은 404(ADMIN_404_5)이다. 대리보기 중에도 쓸 수 있는 쓰기 요청이다.")
    fun end(loginUser: AdminLoginUser, id: UUID, servletRequest: HttpServletRequest): ResponseEntity<Void>
}
