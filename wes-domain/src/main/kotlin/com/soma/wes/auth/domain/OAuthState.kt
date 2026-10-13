package com.soma.wes.auth.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.ZonedDateTime

/**
 * 인가 요청에 실어 보내고 콜백에서 그대로 돌려받는 값.
 */
@Entity
@Table(
    name = "oauth_states",
    indexes = [
        Index(name = "idx_oauth_states_expires_at", columnList = "expires_at"),
    ],
)
class OAuthState(

    @Id
    @Column(length = 64)
    val state: String,

    /** 초대와 무관한 일반 로그인은 null이다. */
    @Column(name = "invite_token", length = 255)
    val inviteToken: String? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: ZonedDateTime,

) : BaseEntity() {

    fun isExpiredAt(at: ZonedDateTime): Boolean = expiresAt.isBefore(at)
}
