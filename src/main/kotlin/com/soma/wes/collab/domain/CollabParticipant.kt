package com.soma.wes.collab.domain

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.ZonedDateTime
import org.hibernate.annotations.SQLRestriction

@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "collab_participants",
    indexes = [Index(name = "idx_collab_participants_session", columnList = "collab_session_id, id")],
)
class CollabParticipant(
    @Column(name = "collab_session_id", nullable = false, updatable = false)
    val collabSessionId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_type", nullable = false, updatable = false, length = 20)
    val participantType: CollabParticipantType,

    @Column(name = "user_id", updatable = false)
    val userId: Long? = null,

    @Column(name = "guest_token", updatable = false, length = 255)
    val guestToken: String? = null,

    @Column(nullable = false, length = MAX_NICKNAME_LENGTH)
    var nickname: String,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 협업 참여자입니다." }

    companion object {
        const val MAX_NICKNAME_LENGTH = 50

        fun guest(sessionId: Long, guestToken: String, nickname: String) = CollabParticipant(
            collabSessionId = sessionId,
            participantType = CollabParticipantType.GUEST,
            guestToken = guestToken,
            nickname = requireValidNickname(nickname),
        )

        fun user(sessionId: Long, userId: Long, nickname: String) = CollabParticipant(
            collabSessionId = sessionId,
            participantType = CollabParticipantType.USER,
            userId = userId,
            nickname = nickname.take(MAX_NICKNAME_LENGTH),
        )

        fun requireValidNickname(nickname: String): String {
            val trimmed = nickname.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_NICKNAME_LENGTH) {
                throw CollabException(CollabErrorCode.INVALID_NICKNAME)
            }
            return trimmed
        }
    }
}

enum class CollabParticipantType { USER, GUEST }
