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
 *
 * OAuth2에서 `state`는 provider가 콜백 쿼리스트링에 그대로 되돌려주는 파라미터다. 브라우저
 * 저장소에 기대지 않고 왕복 사이에 값을 보존할 수 있는 유일한 표준 채널이라, 초대 링크를
 * 누른 사람이 로그인을 마치고 원래 가려던 갤러리로 이어지게 하는 데 쓴다.
 *
 * **[inviteToken]은 여기 남고 state로 나가지 않는다.** 초대 토큰은 가진 사람이 곧 갤러리
 * 멤버가 되는 자격증명이라 provider에 건네면 안 된다. 나가는 것은 [state] 난수뿐이고,
 * 그것이 무엇을 뜻하는지는 이 행만 안다.
 *
 * 한 번 쓰면 지운다([com.soma.wes.auth.support.OAuthStateStore.consume]). 남겨두면 같은
 * state로 콜백을 두 번 태우는 재사용 공격이 열린다.
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
