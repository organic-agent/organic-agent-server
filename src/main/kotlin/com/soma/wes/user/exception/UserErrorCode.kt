package com.soma.wes.user.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class UserErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "USER_400_1", "닉네임은 1~50자로 입력해 주세요."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_404_1", "존재하지 않는 사용자입니다."),
}
