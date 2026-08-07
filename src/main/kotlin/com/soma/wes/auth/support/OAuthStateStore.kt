package com.soma.wes.auth.support

import com.soma.wes.auth.domain.OAuthState
import com.soma.wes.auth.repository.OAuthStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Base64

/**
 * 인가 요청에 실을 `state`를 발급하고, 콜백에서 돌아온 것을 한 번만 받아준다.
 *
 * 발급과 소비가 짝이고 소비는 파괴적이다 — [consume]은 찾은 행을 지운다. 그래야 같은
 * state로 콜백을 두 번 태우는 재사용을 막는다.
 */
@Component
class OAuthStateStore(
    private val oAuthStateRepository: OAuthStateRepository,
    private val clock: Clock,
) {

    companion object {
        /**
         * provider 동의 화면에 머무는 시간만 버티면 된다. 길게 잡을수록 탈취된 state를 쓸 수
         * 있는 창이 넓어진다.
         */
        val VALIDITY: Duration = Duration.ofMinutes(10)

        private const val STATE_BYTES = 32

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private val random = SecureRandom()

    /**
     * @param inviteToken 로그인을 마친 뒤 이어서 수락할 초대. 초대와 무관한 로그인이면 null.
     * @return 인가 URL의 `state`에 실을 난수. 초대 토큰은 여기 실리지 않는다.
     */
    @Transactional
    fun issue(inviteToken: String?): String {
        val bytes = ByteArray(STATE_BYTES)
        random.nextBytes(bytes)
        val state = ENCODER.encodeToString(bytes)

        oAuthStateRepository.save(
            OAuthState(
                state = state,
                inviteToken = inviteToken,
                expiresAt = ZonedDateTime.now(clock).plus(VALIDITY),
            ),
        )
        return state
    }

    /**
     * 콜백이 돌려준 state를 소비하고, 거기 매달린 초대 토큰을 돌려준다.
     *
     * 초대가 없는 정상 로그인과 state 자체가 없거나 만료된 경우를 모두 `null`로 돌려준다.
     * 호출부가 둘을 다르게 다루지 않기 때문이다 — 어느 쪽이든 "로그인만 시키고 끝낸다"이고,
     * 초대를 놓친 사용자는 카톡에 남아 있는 링크를 다시 눌러 복구한다.
     */
    @Transactional
    fun consume(state: String?): String? {
        if (state.isNullOrBlank()) {
            return null
        }

        val found = oAuthStateRepository.findById(state).orElse(null)
        if (found == null) {
            // 이미 쓴 state이거나 우리가 발급하지 않은 값이다. 로그인 자체를 막지는 않지만,
            // 자주 찍히면 프론트가 state를 흘리고 있다는 뜻이다.
            log.warn("알 수 없는 OAuth state로 로그인이 들어왔다")
            return null
        }

        // 만료됐어도 행은 지운다. 남겨두면 아무도 치우지 않는다.
        oAuthStateRepository.delete(found)

        if (found.isExpiredAt(ZonedDateTime.now(clock))) {
            log.info("만료된 OAuth state다. 로그인만 진행한다")
            return null
        }
        return found.inviteToken
    }

    /**
     * 로그인을 시작만 하고 그만둔 사용자의 행을 걷어낸다.
     *
     * [consume]은 콜백이 돌아온 것만 지우므로, 동의 화면에서 창을 닫아버린 요청의 행은
     * 그대로 남는다. 부르지 않으면 테이블이 단조 증가한다.
     */
    @Transactional
    fun purgeExpired(): Long = oAuthStateRepository.deleteAllByExpiresAtBefore(ZonedDateTime.now(clock))
}
