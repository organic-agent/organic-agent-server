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
import java.time.ZonedDateTime
import org.hibernate.annotations.SQLRestriction


@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "collab_sessions",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_collab_sessions_collab_token", columnNames = ["collab_token"]),
    ],
    indexes = [
        Index(name = "idx_collab_sessions_gallery_id", columnList = "gallery_id"),
    ],
)
class CollabSession(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    /** 부부가 링크를 구분하려고 붙인 이름. 하객에게도 첫 화면에 보인다. */
    @Column(nullable = false, length = MAX_NAME_LENGTH)
    var name: String,

    /** 링크에 실리는 값. 추측할 수 없어야 하므로 생성은 `SecureTokenGenerator`가 맡는다. */
    @Column(name = "collab_token", nullable = false, length = 255)
    var collabToken: String,

    /** 부부가 링크를 거둬들인 시각. 링크가 엉뚱한 곳에 퍼졌을 때 쓴다. */
    @Column(name = "revoked_at")
    var revokedAt: ZonedDateTime? = null,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    companion object {
        const val MAX_NAME_LENGTH = 100

        fun requireValidName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
                throw CollabException(CollabErrorCode.INVALID_SESSION_NAME)
            }
            return trimmed
        }

        fun of(galleryId: Long, name: String, collabToken: String) = CollabSession(
            galleryId = galleryId,
            name = requireValidName(name),
            collabToken = collabToken,
        )
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 협업 세션입니다." }

    val isRevoked: Boolean
        get() = revokedAt != null

    fun revoke(at: ZonedDateTime) {
        if (isRevoked) {
            return
        }
        revokedAt = at
    }

    /**
     * 거둬들인 링크를 새 주소로 다시 내보낸다. [revoke]의 짝이다.
     */
    fun republish(collabToken: String) {
        this.collabToken = collabToken
        revokedAt = null
    }

    fun rename(name: String) {
        this.name = requireValidName(name)
    }
}
