package com.soma.wes.admin.resource.controller.docs

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.resource.dto.request.AdminNotificationReadRequest
import com.soma.wes.admin.resource.dto.response.AdminNotificationInboxItemResponse
import com.soma.wes.admin.resource.dto.response.AdminNotificationInboxPageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity

@Tag(name = "Admin Notification Inbox", description = "최고 관리자 알림함. 알림은 모든 관리자가 함께 보고 읽음 여부만 관리자별로 따로 남는다.")
interface AdminNotificationInboxControllerDocs {
    @Operation(summary = "알림 목록", description = "요청한 관리자가 안 읽은 알림을 먼저, 그다음 최신순으로 돌려준다. 전체 수와 안 읽은 수를 함께 준다. page가 0보다 작거나 size가 1~100 밖이면 400(ADMIN_400_6)이다.")
    fun list(loginUser: AdminLoginUser, page: Int, size: Int): ResponseEntity<AdminNotificationInboxPageResponse>

    @Operation(summary = "알림 읽음 처리", description = "읽음은 요청한 관리자에게만 기록된다. 알림이 없으면 404(ADMIN_404_4), expectedVersion이 알림의 version과 다르면 409(ADMIN_409_5)이다. 이미 읽은 알림을 다시 보내도 성공하고 처음 읽은 시각을 유지한다. 대리보기 중에는 403(ADMIN_403_2)이다.")
    fun markRead(loginUser: AdminLoginUser, notificationId: Long, request: AdminNotificationReadRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminNotificationInboxItemResponse>
}
