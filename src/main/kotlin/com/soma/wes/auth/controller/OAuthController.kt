package com.soma.wes.auth.controller

import com.soma.wes.auth.controller.docs.OAuthControllerDocs
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.dto.response.LoginUrlResponse
import com.soma.wes.auth.service.oauth.OAuthLoginProcessor
import com.soma.wes.auth.service.oauth.OAuthLoginUrlService
import com.soma.wes.auth.service.oauth.OAuthUserInfoService
import org.springframework.web.bind.annotation.*

/**
 * provider는 [OAuthProvider] 타입이 아니라 `String`으로 받아 [OAuthProvider.from]으로 직접 변환한다.
 *
 * 파라미터를 enum으로 선언하면 변환이 프레임워크 손에 넘어간다. Spring은 변환 실패를
 * `Enum.valueOf()`로 되돌려 보려 하면서 변환기가 던진 예외를 삼키므로, 지원하지 않는 provider가
 * `AUTH_400_1`이 아니라 `GLOBAL_400_1`("요청 값이 올바르지 않습니다")로 뭉개진다.
 * 여기서 직접 변환하면 우리 예외가 그대로 올라온다.
 */
@RestController
@RequestMapping("/api/v1/oauth")
class OAuthController(
    private val oAuthLoginUrlService: OAuthLoginUrlService,
    private val oAuthUserInfoService: OAuthUserInfoService,
    private val oAuthLoginProcessor: OAuthLoginProcessor,
) : OAuthControllerDocs {

    @GetMapping("/login-url/{provider}")
    override fun loginUrl(
        @PathVariable provider: String,
    ): LoginUrlResponse =
        LoginUrlResponse.from(oAuthLoginUrlService.generateLoginUrl(OAuthProvider.from(provider)))

    @PostMapping("/{provider}")
    override fun login(
        @PathVariable provider: String,
        @RequestBody request: AuthCodeRequest,
    ): LoginResponse {
        val userInfo = oAuthUserInfoService.getUserInfo(OAuthProvider.from(provider), request.code)
        return oAuthLoginProcessor.process(userInfo)
    }
}
