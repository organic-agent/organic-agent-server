package com.soma.wes.admin.support

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

@Component
class AdminSecretGenerator {

    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return ENCODER.encodeToString(bytes)
    }

    companion object {
        private const val SECRET_BYTES = 32
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
