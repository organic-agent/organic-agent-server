package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.auth.dto.response.LoginResponse
import org.springframework.stereotype.Service


@Service
class OAuthLoginService(
    private val oAuthUserInfoService: OAuthUserInfoService,
    private val oAuthLoginProcessor: OAuthLoginProcessor,
) {

    fun login(provider: String, request: AuthCodeRequest, requestOrigin: String? = null): LoginResponse {
        val userInfo = oAuthUserInfoService.getUserInfo(
            OAuthProvider.from(provider),
            request.code,
            requestOrigin,
        )

        return oAuthLoginProcessor.process(userInfo)
    }
}
