package com.soma.wes.auth.dto.request

/**
 * `code`를 non-null로 선언해두면 JSON에 값이 없을 때 역직렬화 단계에서 걸러져 400이 응답된다.
 *
 * [state]는 콜백 쿼리스트링에 실려 돌아온 값을 그대로 넘기면 된다. 프론트가 해석할 필요도,
 * 보관할 필요도 없다 — 무엇을 뜻하는지는 서버만 안다.
 *
 * nullable인 이유는 아직 state를 보내지 않는 클라이언트가 있기 때문이다. 없으면 초대 연결
 * 없이 로그인만 되고, 전부 보내게 되면 그때 non-null로 좁힌다.
 */
data class AuthCodeRequest(
    val code: String,
    val state: String? = null,
)
