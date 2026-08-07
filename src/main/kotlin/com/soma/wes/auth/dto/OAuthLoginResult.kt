package com.soma.wes.auth.dto

import com.soma.wes.auth.dto.response.LoginResponse

/**
 * [com.soma.wes.auth.service.oauth.OAuthLoginProcessor.process]가 돌려주는 것.
 *
 * 응답에는 사용자 id가 실리지 않지만, 로그인에 이어 초대를 수락하려면 호출부가 "방금 누가
 * 로그인했는지"를 알아야 한다. 응답 DTO에 id를 노출하는 대신 여기서만 들고 다닌다.
 */
data class OAuthLoginResult(
    val userId: Long,
    val response: LoginResponse,
)