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

        const val MAX_NICKNAME_LENGTH = 20

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
