package com.soma.wes.gallery.service

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64


@Component
class GalleryInviteTokenGenerator {

    private val random = SecureRandom()

    companion object {
        private const val TOKEN_BYTES = 32

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return ENCODER.encodeToString(bytes)
    }
}
