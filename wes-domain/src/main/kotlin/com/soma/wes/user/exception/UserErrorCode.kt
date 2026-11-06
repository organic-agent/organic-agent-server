package com.soma.wes.user.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class UserErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "USER_400_1", "닉네임은 1~50자로 입력해 주세요."),
    /** 색 키가 웹이 정한 7개(`UserColor`) 밖이다. 대소문자도 같아야 한다 — 웹은 소문자 키만 보낸다. */
    INVALID_COLOR(HttpStatus.BAD_REQUEST, "USER_400_2", "선택할 수 없는 색입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_404_1", "존재하지 않는 사용자입니다."),
}
