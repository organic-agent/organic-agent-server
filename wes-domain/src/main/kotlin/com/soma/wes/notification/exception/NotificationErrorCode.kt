package com.soma.wes.notification.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class NotificationErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_READ_REQUEST(HttpStatus.BAD_REQUEST, "NOTIFICATION_400_1", "알림 id 목록 또는 모두 읽음을 지정해 주세요."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_404_1", "읽을 수 없는 알림이 포함되어 있습니다."),
}
