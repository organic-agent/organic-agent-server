package com.soma.wes.photo

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/** 미리보기 CDN 테스트용 RSA 키. 운영처럼 PKCS#8 PEM으로 넘겨 어댑터의 키 읽기까지 지나게 한다. */
object PreviewCdnTestKeys {

    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val privatePem: String = buildString {
        appendLine("-----BEGIN PRIVATE KEY-----")
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded).lines().forEach { appendLine(it) }
        appendLine("-----END PRIVATE KEY-----")
    }
}
