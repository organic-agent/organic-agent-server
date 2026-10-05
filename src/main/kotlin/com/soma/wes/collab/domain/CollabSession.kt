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
        UniqueConstraint(name = "uk_collab_sessions_concept_folder", columnNames = ["concept_folder_id"]),
    ],
    indexes = [
        Index(name = "idx_collab_sessions_gallery_id", columnList = "gallery_id"),
    ],
)
class CollabSession(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    /**
     * 옛 컨셉 연결 공유폴더의 흔적. 공유폴더는 컨셉·세부 폴더와 따로 살기로 해 V36부터 항상 `null`이다.
     * 관리자 화면·휴지통 SQL이 아직 이 컬럼을 읽어서 컬럼을 지울 때까지 남긴다.
     */
    @Column(name = "concept_folder_id", nullable = true, updatable = false)
    val conceptFolderId: Long? = null,

    /** 부부가 링크를 구분하려고 붙인 이름. 하객에게도 첫 화면에 보인다. */
    @Column(nullable = false, length = MAX_NAME_LENGTH)
    var name: String,

    /** 링크에 실리는 값. 추측할 수 없어야 하므로 생성은 `SecureTokenGenerator`가 맡는다. */
    @Column(name = "collab_token", nullable = false, length = 255)
    var collabToken: String,

    /** 부부가 링크를 거둬들인 시각. 링크가 엉뚱한 곳에 퍼졌을 때 쓴다. */
    @Column(name = "revoked_at")
    var revokedAt: ZonedDateTime? = null,

    /**
     * 운영자가 재발급한 제한 시간 링크의 만료 시각. `null`은 부부가 직접 만든 일반 링크처럼
     * 만료 기한이 없다는 뜻이다.
     */
    @Column(name = "expires_at")
    var expiresAt: ZonedDateTime? = null,

) : BaseEntity() {

    /** 사진 변경도 세션 버전에 반영해 관리자 편집이 오래된 상태를 덮어쓰지 않게 한다. */
    fun photosChanged(at: ZonedDateTime) {
        updatedAt = at
    }

    @Column(name = "include_all_albums", nullable = false)
    var includeAllAlbums: Boolean = false

    @Column(name = "cover_title", length = MAX_NAME_LENGTH)
    var coverTitle: String? = null

    @Column(name = "cover_author", length = MAX_NAME_LENGTH)
    var coverAuthor: String? = null

    fun updateCover(title: String?, author: String?) {
        if (title != null) coverTitle = if (title.isBlank()) null else requireValidName(title)
        if (author != null) coverAuthor = if (author.isBlank()) null else requireValidName(author)
    }

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

    fun isExpiredAt(now: ZonedDateTime): Boolean = expiresAt?.let { !it.isAfter(now) } ?: false

    fun revoke(at: ZonedDateTime) {
        if (isRevoked) {
            return
        }
        revokedAt = at
    }

    /**
     * 거둬들인 링크를 새 주소로 다시 내보낸다. [revoke]의 짝이다.
     */
    fun republish(collabToken: String, expiresAt: ZonedDateTime? = null) {
        this.collabToken = collabToken
        revokedAt = null
        // 부부가 직접 다시 발행한 링크는 일반 제품 링크다. 이전 운영자 발급 TTL을 이어받지 않는다.
        this.expiresAt = expiresAt
    }

    fun rename(name: String) {
        this.name = requireValidName(name)
    }
}
