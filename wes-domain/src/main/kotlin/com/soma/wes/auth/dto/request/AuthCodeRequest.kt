package com.soma.wes.auth.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class AuthCodeRequest(
    @field:Schema(
        description = "provider 콜백 쿼리스트링의 인가 코드. 빠지면 400이고, 퍼센트 인코딩된 채로 보내도 서버가 디코딩한다.",
        example = "4/0AeanS0Zx8kY2mQ7vR1tW9pL3cN6bH5jD",
    )
    val code: String,
    @field:Schema(
        description = "콜백 쿼리스트링에 실려 돌아온 state를 해석하지 말고 그대로 넘긴다. 한 번 쓰면 사라지며, 생략하거나 이미 썼거나 발급 10분이 지난 값이면 초대 연결 없이 로그인만 된다.",
        example = "Q3k9vX2mT8pL1nR6wY4zB7cD0fG5hJ2sA9eK3uV8xM1",
    )
    val state: String? = null,
)
