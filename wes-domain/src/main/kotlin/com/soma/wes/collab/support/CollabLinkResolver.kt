package com.soma.wes.collab.support

import com.soma.wes.collab.config.CollabProperties
import org.springframework.stereotype.Component

/**
 * 협업 토큰을 하객에게 그대로 보낼 수 있는 협업 링크로 만든다. 응답의 `collabUrl`이 여기서 나온다.
 */
@Component
class CollabLinkResolver(
    private val properties: CollabProperties,
) {

    fun resolve(collabToken: String): String = "${properties.baseUrl.trimEnd('/')}/$collabToken"
}
