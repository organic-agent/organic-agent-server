package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.support.OAuthStateStore
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.service.GalleryInviteService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service


@Service
class OAuthLoginService(
    private val oAuthUserInfoService: OAuthUserInfoService,
    private val oAuthLoginProcessor: OAuthLoginProcessor,
    private val oAuthStateStore: OAuthStateStore,
    private val galleryInviteService: GalleryInviteService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

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

        return result.response.copy(galleryId = acceptOrNull(inviteToken, result.userId))
    }

    /**
     * 초대 수락이 실패해도 로그인은 살린다.
     *
     * 만료·폐기된 링크나 자기 갤러리를 수락하려는 작가는 여기서 걸리는데, 그렇다고 로그인까지
     * 실패시키면 사용자는 이유도 모른 채 아무 데도 못 간다. 로그인은 끝내주고 갤러리만 비워
     * 보내면, 사용자가 카톡에 남아 있는 링크를 다시 눌렀을 때 수락 API가 410·403으로 정확한
     * 이유를 알려준다.
     */
    private fun acceptOrNull(inviteToken: String, userId: Long): Long? =
        try {
            galleryInviteService.accept(inviteToken, userId).galleryId
        } catch (e: GalleryException) {
            // 스택 트레이스를 남기지 않는다. 만료된 링크는 결함이 아니라 예상된 사용자 상황이라,
            // 무엇이 걸렀는지는 code 하나로 충분하다.
            log.info("로그인은 성공했지만 초대 수락에 실패했다: userId={}, code={}", userId, e.errorCode.code)
            null
        }
}
