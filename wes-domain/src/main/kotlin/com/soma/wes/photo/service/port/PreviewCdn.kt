package com.soma.wes.photo.service.port

import com.soma.wes.photo.dto.PreviewAccessDto

/**
 * 미리보기를 CDN 고정 주소로 내주는 통로(#336 ADR-0001). 꺼져 있으면 [enabled]가 false이고 나머지는 부르지 않는다.
 */
interface PreviewCdn {

    val enabled: Boolean

    /** 이 키가 CDN으로 나갈 수 있는 미리보기인가. 원본·보정 결과처럼 쿠키 경로 밖의 키는 서명 URL로 남는다. */
    fun serves(key: String): Boolean

    /** 미리보기 키의 고정 주소. 만료가 없다 — 열 수 있는지는 쿠키가 정한다. */
    fun urlOf(key: String): String

    /** 이 주소가 [urlOf]로 낸 고정 주소인가 — 아니면 만료되는 서명 URL이다. */
    fun isFixedUrl(url: String): Boolean

    /** 갤러리 하나의 미리보기 경로에만 맞는 쿠키. 권한 확인은 부르는 쪽이 먼저 한다. */
    fun accessFor(galleryId: Long): PreviewAccessDto
}
