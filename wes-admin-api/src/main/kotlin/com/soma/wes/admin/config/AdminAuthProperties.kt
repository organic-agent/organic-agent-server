package com.soma.wes.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.admin")
data class AdminAuthProperties(
    val session: Session,
    val lockout: Lockout,
) {

    data class Session(
        val absoluteTtl: Duration,
        val idleTtl: Duration,
    )

    data class Lockout(
        val maxFailedAttempts: Int,
        val duration: Duration,
    )
}
