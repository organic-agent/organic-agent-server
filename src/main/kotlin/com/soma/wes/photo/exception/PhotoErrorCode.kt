package com.soma.wes.photo.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class PhotoErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "PHOTO_404_1", "존재하지 않는 사진입니다."),
}
