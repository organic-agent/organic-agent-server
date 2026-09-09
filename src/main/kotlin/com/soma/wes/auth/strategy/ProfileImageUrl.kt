package com.soma.wes.auth.strategy

import java.net.URI

/** 선택 동의 항목이 없거나 잘못된 사진 URL이어도 로그인 자체는 유지한다. */
internal fun Any?.asProfileImageUrl(): String? {
    val value = (this as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    return value.takeIf {
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
}
