package com.soma.wes.admin.support

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

@Component
class AdminSessionTokenHasher {

    fun hash(rawToken: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(rawToken.toByteArray(StandardCharsets.UTF_8)),
        )
}
