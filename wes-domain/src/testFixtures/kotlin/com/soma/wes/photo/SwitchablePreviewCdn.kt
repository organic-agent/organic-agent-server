package com.soma.wes.photo

import com.soma.wes.photo.config.PreviewCdnProperties
import com.soma.wes.photo.dto.PreviewAccessDto
import com.soma.wes.photo.infrastructure.CloudFrontPreviewCdn
import com.soma.wes.photo.service.port.PreviewCdn
import java.time.Clock

/**
 * 테스트마다 켜고 끄는 미리보기 CDN. 꺼진 상태가 기본이라 운영 기본값(꺼짐)과 같고, 켜면 실제 어댑터를 테스트 키로 부른다.
 *
 * 켠 상태를 전용 컨텍스트(`@Primary` 교체)로 만들지 않는 까닭: 컨텍스트가 하나 늘 때마다 그 안의 주기 작업이 테스트 사이 TRUNCATE와
 * 겹쳐 교착할 기회가 는다(#336 W1에서 한 번 재현). 공용 컨텍스트 하나에서 켜고 끈다 — 켠 테스트는 `@AfterEach`에서 반드시 끈다.
 */
class SwitchablePreviewCdn(clock: Clock) : PreviewCdn {

    private val delegate = CloudFrontPreviewCdn(
        properties = PreviewCdnProperties(
            enabled = true,
            baseUrl = BASE_URL,
            keyPairId = "KTEST123",
            privateKey = PreviewCdnTestKeys.privatePem,
            cookieDomain = "easyselect.kr",
        ),
        clock = clock,
    )

    @Volatile
    private var on = false

    companion object {
        const val BASE_URL = "https://img.test"
    }

    fun turnOn() {
        on = true
    }

    fun turnOff() {
        on = false
    }

    override val enabled: Boolean
        get() = on

    override fun serves(key: String): Boolean = on && delegate.serves(key)

    override fun urlOf(key: String): String = delegate.urlOf(key)

    override fun isFixedUrl(url: String): Boolean = on && delegate.isFixedUrl(url)

    override fun accessFor(galleryId: Long): PreviewAccessDto = if (on) delegate.accessFor(galleryId) else PreviewAccessDto.DISABLED
}
