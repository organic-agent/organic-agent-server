package com.soma.wes.cluster.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class ClusterErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /** 코사인 유사도는 정의상 0.0~1.0이다. 범위를 벗어난 값은 거리로 환산하면 의미가 없다. */
    INVALID_THRESHOLD(HttpStatus.BAD_REQUEST, "CLUSTER_400_1", "유사도는 0.0 이상 1.0 이하여야 합니다."),
}
