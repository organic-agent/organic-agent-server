package com.soma.wes.collab.support

/**
 * 하객 세션 토큰을 싣는 헤더.
 *
 * 쿼리 파라미터로 받지 않는다. URL은 ALB 액세스 로그와 브라우저의 `Referer`, 그리고 하객이
 * 그대로 복사해 보내는 주소창에 남는다 — 토큰을 거기에 실으면 링크를 나눠 갖는 순간 그 사람의
 * 이름으로 글을 쓸 권한까지 함께 나눠 갖게 된다.
 *
 * `Authorization`을 쓰지 않는 이유는 이 값이 로그인 토큰이 아니기 때문이다. 같은 헤더에 실으면
 * JWT 필터가 지나는 경로와 섞여, 어느 쪽 토큰이 와야 하는 경로인지 구분이 사라진다.
 */
object GuestTokenHeader {

    const val GUEST_TOKEN = "X-Guest-Token"
}
