package com.soma.wes.photo.infrastructure

import com.soma.wes.photo.PreviewCdnTestKeys
import com.soma.wes.photo.config.PreviewCdnProperties
import com.soma.wes.photo.dto.PreviewAccessDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * 쿠키가 CloudFront가 받아 주는 모양인지 — 서명이 공개키로 맞고, 정책이 그 갤러리 경로만 열고, 수명이 설정대로인지 — 를 스프링 없이 본다.
 * 실제 CloudFront 거절·허용은 10-19 T2(임시 배포)에서 확인했다. 여기서는 그때와 같은 모양을 앱이 만드는지를 지킨다.
 */
class CloudFrontPreviewCdnUnitTest {

    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun cdn(enabled: Boolean = true, pathPrefix: String = "d", privateKey: String = PreviewCdnTestKeys.privatePem) =
        CloudFrontPreviewCdn(
            properties = PreviewCdnProperties(
                enabled = enabled,
                baseUrl = "https://img-dev.easyselect.kr",
                pathPrefix = pathPrefix,
                keyPairId = "KTEST123",
                privateKey = privateKey,
                cookieDomain = "easyselect.kr",
                cookieTtl = Duration.ofMinutes(20),
                refreshAfter = Duration.ofMinutes(10),
            ),
            clock = clock,
        )

    @Nested
    @DisplayName("주소를 만들 때")
    inner class Url {

        @Test
        fun `미리보기 키는 환경 접두를 붙인 고정 주소가 된다`() {
            // dev 쿠키 경로(/d/previews/…)가 운영 쿠키 경로(/previews/…)와 겹치지 않아야 같은 상위 도메인에서 서로 덮어쓰지 않는다.
            assertSoftly { softly ->
                softly.assertThat(cdn().urlOf("previews/galleries/52/a.jpg"))
                    .isEqualTo("https://img-dev.easyselect.kr/d/previews/galleries/52/a.jpg")
                softly.assertThat(cdn(pathPrefix = "").urlOf("previews/galleries/52/a.jpg"))
                    .isEqualTo("https://img-dev.easyselect.kr/previews/galleries/52/a.jpg")
            }
        }

        @Test
        fun `미리보기만 맡고 원본·보정 결과는 맡지 않는다`() {
            // 쿠키는 미리보기 경로만 연다. 원본은 짧은 서명 URL로 남는다(C1).
            assertSoftly { softly ->
                softly.assertThat(cdn().serves("previews/galleries/52/a.jpg")).isTrue()
                softly.assertThat(cdn().serves("galleries/52/a.jpg")).isFalse()
                softly.assertThat(cdn().serves("galleries/52/retouch/results/1/a.jpg")).isFalse()
                softly.assertThat(cdn(enabled = false).serves("previews/galleries/52/a.jpg")).isFalse()
            }
        }

        @Test
        fun `고정 주소와 서명 URL을 가른다`() {
            assertSoftly { softly ->
                softly.assertThat(cdn().isFixedUrl("https://img-dev.easyselect.kr/d/previews/galleries/52/a.jpg")).isTrue()
                softly.assertThat(cdn().isFixedUrl("https://bucket.s3.ap-northeast-2.amazonaws.com/previews/a.jpg?X-Amz-Signature=x")).isFalse()
                softly.assertThat(cdn(enabled = false).isFixedUrl("https://img-dev.easyselect.kr/d/previews/galleries/52/a.jpg")).isFalse()
            }
        }
    }

    @Nested
    @DisplayName("쿠키를 만들 때")
    inner class Access {

        @Test
        fun `세 쿠키를 그 갤러리 미리보기 경로에 설정한 수명으로 준다`() {
            // when
            val access = cdn().accessFor(52)

            // then
            assertSoftly { softly ->
                softly.assertThat(access.cookies.map { it.name })
                    .containsExactly("CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id")
                softly.assertThat(access.cookies.map { it.path }.toSet()).containsExactly("/d/previews/galleries/52/")
                softly.assertThat(access.cookies.map { it.domain }.toSet()).containsExactly("easyselect.kr")
                softly.assertThat(access.cookies.map { it.maxAge }.toSet()).containsExactly(Duration.ofMinutes(20))
                softly.assertThat(access.cookies.last().value).isEqualTo("KTEST123")
                softly.assertThat(access.expiresAt).isEqualTo(now.plus(Duration.ofMinutes(20)))
                softly.assertThat(access.refreshAfterSeconds).isEqualTo(600)
            }
        }

        @Test
        fun `정책은 그 갤러리 경로만 열고 서명은 공개키로 맞는다`() {
            // 다른 갤러리 경로가 정책에 들어가면 쿠키 하나로 남의 갤러리 사진이 열린다(T2 b의 403이 깨짐).
            // when
            val access = cdn().accessFor(52)
            val policy = String(cloudFrontDecode(access.cookies[0].value))
            val signature = cloudFrontDecode(access.cookies[1].value)

            // then
            val verifier = Signature.getInstance("SHA1withRSA").apply {
                initVerify(PreviewCdnTestKeys.keyPair.public)
                update(policy.toByteArray())
            }
            assertSoftly { softly ->
                softly.assertThat(policy).contains("\"https://img-dev.easyselect.kr/d/previews/galleries/52/*\"")
                softly.assertThat(policy).contains("${now.plus(Duration.ofMinutes(20)).epochSecond}")
                softly.assertThat(verifier.verify(signature)).isTrue()
            }
        }

        @Test
        fun `꺼져 있으면 쿠키 없이 같은 모양을 준다`() {
            assertThat(cdn(enabled = false, privateKey = "").accessFor(52)).isEqualTo(PreviewAccessDto.DISABLED)
        }

        @Test
        fun `PKCS#8이 아닌 키는 켜는 순간 알린다`() {
            // openssl genrsa 의 PKCS#1(BEGIN RSA PRIVATE KEY)은 JDK가 읽지 못한다 — 조용히 틀린 쿠키를 내지 않고 500으로 드러낸다.
            assertThatThrownBy { cdn(privateKey = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----").accessFor(52) }
                .isInstanceOf(IllegalStateException::class.java)
        }

        /** CloudFront식 base64(+ → -, = → _, / → ~)를 되돌린다. */
        private fun cloudFrontDecode(value: String): ByteArray =
            Base64.getDecoder().decode(value.replace('-', '+').replace('_', '=').replace('~', '/'))
    }
}
