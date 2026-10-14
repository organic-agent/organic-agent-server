package com.soma.wes.gallery.support

import com.soma.wes.gallery.config.GalleryInviteProperties
import org.springframework.stereotype.Component

/**
 * 초대 토큰을 사람이 그대로 눌러볼 수 있는 URL로 만든다. 응답의 `inviteUrl`이 여기서 나온다.
 */
@Component
class GalleryInviteUrlResolver(
    private val properties: GalleryInviteProperties,
) {

    fun resolve(token: String): String = "${properties.baseUrl.trimEnd('/')}/$token"
}
