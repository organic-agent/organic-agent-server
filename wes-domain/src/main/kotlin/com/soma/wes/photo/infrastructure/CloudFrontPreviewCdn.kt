package com.soma.wes.photo.infrastructure

import com.soma.wes.photo.config.PreviewCdnProperties
import com.soma.wes.photo.dto.PreviewAccessDto
import com.soma.wes.photo.dto.PreviewCookieDto
import com.soma.wes.photo.service.port.PreviewCdn
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64

/**
 * 미리보기를 CloudFront 고정 주소 + 서명 쿠키로 내준다(#336 ADR-0001).
 *
 * 주소는 `{baseUrl}/{pathPrefix}/previews/galleries/{id}/…`로 만료가 없다. 열 수 있는지는 갤러리 경로(`…/previews/galleries/{id}/` 아래 전부)에만
 * 맞는 커스텀 정책 쿠키가 정한다 — 다른 갤러리 경로는 같은 쿠키로 403이다(10-19 T2 확인). 서명은 개인키로 로컬에서 계산하므로 네트워크를 타지 않는다.
 *
 * 쿠키 세 개(Policy · Signature · Key-Pair-Id)는 SDK가 만든 `이름=값` 헤더 값을 그대로 나눠 쓴다 — 인코딩(CloudFront식 base64)을
 * 직접 흉내 내지 않는다.
 */
@Component
class CloudFrontPreviewCdn(
    private val properties: PreviewCdnProperties,
    private val clock: Clock,
) : PreviewCdn {

    private val utilities = CloudFrontUtilities.create()

    /**
     * 켜져 있을 때만 읽는다. 꺼진 환경(로컬·테스트·관리자 앱)은 키가 없어도 떠야 한다.
     * 켜져 있으면 기동할 때 읽어, 잘못된 키가 첫 쿠키 발급에서야 드러나지 않게 한다 — 롤링 배포에서 그 서버가 뜨지 못해 배포가 멈춘다.
     */
    private val privateKey: PrivateKey? = if (properties.enabled) parsePkcs8(properties.privateKey) else null

    companion object {
        /** 미리보기 키의 머리. 쿠키는 이 아래 갤러리 경로만 연다 — 원본(`galleries/…`)·보정 결과는 범위 밖이다. */
        private const val PREVIEW_ROOT = "previews/galleries/"

        private const val PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_END = "-----END PRIVATE KEY-----"
    }

    init {
        if (properties.enabled) {
            check(properties.baseUrl.startsWith("https://")) {
                "미리보기 CDN 주소가 https 절대 주소가 아니다(app.storage.preview-cdn.base-url) — 상대 주소를 고정 주소로 내주게 된다"
            }
            check(properties.keyPairId.isNotBlank()) { "미리보기 CDN 키 ID가 비어 있다(app.storage.preview-cdn.key-pair-id)" }
            check(properties.cookieDomain.isNotBlank()) { "미리보기 쿠키 도메인이 비어 있다(app.storage.preview-cdn.cookie-domain)" }
        }
    }

    override val enabled: Boolean
        get() = properties.enabled

    override fun serves(key: String): Boolean = enabled && key.startsWith(PREVIEW_ROOT)

    override fun urlOf(key: String): String = "${properties.baseUrl.trimEnd('/')}${pathOf(key)}"

    override fun isFixedUrl(url: String): Boolean = enabled && url.startsWith("${properties.baseUrl.trimEnd('/')}/")

    override fun accessFor(galleryId: Long): PreviewAccessDto {
        if (!enabled) return PreviewAccessDto.DISABLED

        val expiresAt = clock.instant().plus(properties.cookieTtl)
        val cookiePath = pathOf("$PREVIEW_ROOT$galleryId/")
        val signed = utilities.getCookiesForCustomPolicy(
            CustomSignerRequest.builder()
                .resourceUrl("${properties.baseUrl.trimEnd('/')}$cookiePath")
                .resourceUrlPattern("${properties.baseUrl.trimEnd('/')}$cookiePath*")
                .privateKey(checkNotNull(privateKey) { "켜진 CDN인데 개인키를 읽지 않았다" })
                .keyPairId(properties.keyPairId)
                .expirationDate(expiresAt)
                .build(),
        )
        val cookies = listOf(signed.policyHeaderValue(), signed.signatureHeaderValue(), signed.keyPairIdHeaderValue())
            .map { header ->
                PreviewCookieDto(
                    name = header.substringBefore('='),
                    value = header.substringAfter('='),
                    domain = properties.cookieDomain,
                    path = cookiePath,
                    maxAge = properties.cookieTtl,
                )
            }

        return PreviewAccessDto(cookies = cookies, expiresAt = expiresAt, refreshAfterSeconds = properties.refreshAfter.seconds)
    }

    /** 환경 접두를 붙인 경로. dev 쿠키(`/d/previews/…`)와 운영 쿠키(`/previews/…`)가 같은 상위 도메인에서 겹치지 않게 한다. */
    private fun pathOf(key: String): String {
        val prefix = properties.pathPrefix.trim('/')
        return if (prefix.isEmpty()) "/$key" else "/$prefix/$key"
    }

    /** PKCS#8 PEM만 받는다(`openssl genpkey -algorithm RSA`). PKCS#1(`BEGIN RSA PRIVATE KEY`)은 JDK가 읽지 못한다. */
    private fun parsePkcs8(pem: String): PrivateKey {
        check(pem.contains(PEM_BEGIN)) { "미리보기 CDN 개인키가 PKCS#8 PEM이 아니다(app.storage.preview-cdn.private-key)" }
        val body = pem.substringAfter(PEM_BEGIN).substringBefore(PEM_END).filterNot { it.isWhitespace() }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }
}
