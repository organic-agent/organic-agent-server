package com.soma.wes.admin.support

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component

@Component
class AdminPasswordHasher {

    private val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val dummyHash = encoder.encode("wes-admin-password-timing-guard")

    fun hash(rawPassword: String): String {
        validate(rawPassword)
        return encoder.encode(rawPassword)
            ?: throw AdminException(AdminErrorCode.PASSWORD_HASH_FAILED)
    }

    fun matches(
        rawPassword: String,
        passwordHash: String,
    ): Boolean = encoder.matches(rawPassword.take(MAX_LENGTH), passwordHash)

    fun consumeDummyMatch(rawPassword: String) {
        encoder.matches(rawPassword.take(MAX_LENGTH), dummyHash)
    }

    fun needsUpgrade(passwordHash: String): Boolean = encoder.upgradeEncoding(passwordHash)

    private fun validate(rawPassword: String) {
        if (rawPassword.length !in MIN_LENGTH..MAX_LENGTH) {
            throw AdminException(AdminErrorCode.PASSWORD_POLICY_VIOLATION)
        }
    }

    companion object {
        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 128
    }
}
