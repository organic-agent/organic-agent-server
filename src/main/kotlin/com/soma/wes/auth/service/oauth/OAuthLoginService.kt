package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.support.OAuthStateStore
import org.springframework.stereotype.Service


@Service
class OAuthLoginService(
    private val oAuthUserInfoService: OAuthUserInfoService,
    private val oAuthLoginProcessor: OAuthLoginProcessor,
    private val oAuthStateStore: OAuthStateStore,
) {


    fun login(provider: String, request: AuthCodeRequest, requestOrigin: String? = null): LoginResponse {
        val userInfo = oAuthUserInfoService.getUserInfo(
            OAuthProvider.from(provider),
            request.code,
            requestOrigin,
        )

        // 콜백 하나당 한 번만 쓴다. 결과와 무관하게 여기서 태워버려야 같은 state로 두 번
        // 들어오는 재사용을 막는다.
        val inviteToken = oAuthStateStore.consume(request.state)

        val result = oAuthLoginProcessor.process(userInfo)
        if (inviteToken == null) {
            return result.response
        }

        return result.response.copy(inviteToken = inviteToken)
    }
}
