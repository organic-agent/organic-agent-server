package com.soma.wes.collab.domain

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 협업 세션에 들어온 하객 한 사람.
 *
 * 계정이 아니다. 닉네임을 적고 들어오면 서버가 [guestToken]을 발급하고, 그 값을 가진 요청을
 * 같은 사람으로 본다. 브라우저를 바꾸거나 저장된 토큰을 지우면 다른 사람이 된다 — 하객에게
 * 회원가입을 요구할 수는 없으므로 이것이 우리가 알 수 있는 "본인"의 전부다.
 *
 * **토큰을 클라이언트가 만들지 않는다.** 값을 스스로 정할 수 있으면 남의 토큰을 그대로 적어
 * 그 사람 이름으로 글을 쓰거나 그 사람의 표를 뒤집을 수 있다. 서버가 32바이트 난수로 발급하면
 * 적어도 남의 것을 추측할 수는 없다.
 *
 * 닉네임이 댓글·반응 행이 아니라 여기 한 곳에만 있다. 복사해두면 같은 사람이 댓글마다 다른
 * 이름을 쓸 수 있고, 이름을 고쳤을 때 과거 댓글이 남이 쓴 것처럼 보인다.
 */
@Entity
@Table(
    name = "collab_guests",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_collab_guests_guest_token", columnNames = ["guest_token"]),
    ],
    indexes = [
        Index(name = "idx_collab_guests_collab_session_id", columnList = "collab_session_id"),
    ],
)
class CollabGuest(

    @Column(name = "collab_session_id", nullable = false, updatable = false)
    val collabSessionId: Long,

    @Column(name = "guest_token", nullable = false, updatable = false, length = 255)
    val guestToken: String,

    @Column(nullable = false, length = MAX_NICKNAME_LENGTH)
    var nickname: String,

) : BaseEntity() {

    companion object {

        /**
         * 화면에 이름표로 붙는 값이라 길면 사진을 덮는다. 20자면 "신랑 대학 동기 김철수"가 들어간다.
         */
        const val MAX_NICKNAME_LENGTH = 20

        /**
         * 닉네임이 들어오는 유일한 문.
         *
         * DTO의 `@field:Size`로 두지 않는다. 그 검증은 컨트롤러가 `@Valid`를 붙였을 때만 돌아서,
         * 다른 곳에서 서비스를 부르면 검사 없이 하객 행이 만들어진다.
         *
         * 중복은 막지 않는다. "친구"가 셋이어도 각자 다른 [guestToken]을 들고 있어 서로 다른
         * 사람이고, 이름이 겹친다고 나중에 들어온 하객을 돌려보낼 이유가 없다.
         */
        fun requireValidNickname(nickname: String): String {
            val trimmed = nickname.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_NICKNAME_LENGTH) {
                throw CollabException(CollabErrorCode.INVALID_NICKNAME)
            }
            return trimmed
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 하객입니다." }
}
