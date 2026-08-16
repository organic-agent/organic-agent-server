package com.soma.wes.cluster.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class ClusterErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /** 레벨은 서버 설정에 정의된 프리셋(1~5)만 유효하다. 임의 숫자는 대응하는 번들이 없다. */
    INVALID_LEVEL(HttpStatus.BAD_REQUEST, "CLUSTER_400_1", "지원하지 않는 묶음 레벨입니다."),
}
