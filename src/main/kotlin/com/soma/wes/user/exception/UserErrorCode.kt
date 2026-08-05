package com.soma.wes.user.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class UserErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    USER_TYPE_ALREADY_SELECTED(HttpStatus.CONFLICT, "USER_409_1", "이미 사용자 종류가 정해졌습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_404_1", "존재하지 않는 사용자입니다."),
}
