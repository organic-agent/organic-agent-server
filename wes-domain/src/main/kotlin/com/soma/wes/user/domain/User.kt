package com.soma.wes.user.domain

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.global.BaseEntity
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.exception.UserErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.ZonedDateTime

/**
 * 서비스의 단일 신원. 사진작가든 예비 부부든 계정은 이 테이블 하나다.
 */
@Entity
@SQLRestriction("deleted_at is null")
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_users_provider_provider_id", columnNames = ["provider", "provider_id"]),
    ],
)
class User(

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    val provider: OAuthProvider,

    @Column(name = "provider_id", nullable = false, updatable = false)
    val providerId: String,

    nickname: String,

    email: String? = null,

    role: Role = Role.USER,

    profileImageUrl: String? = null,

) : BaseEntity() {

    @Column(name = "deleted_at", nullable = true)
    var deletedAt: ZonedDateTime? = null
        protected set

    @Column(name = "suspended_at", nullable = true)
    var suspendedAt: ZonedDateTime? = null
        protected set

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 사용자입니다." }

    @Column(name = "nickname", nullable = false, length = 50)
    var nickname: String = nickname
        protected set

    @Column(name = "email", nullable = true)
    var email: String? = email
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: Role = role
        protected set

    @Column(name = "profile_image_url", nullable = true, length = 2048)
    var profileImageUrl: String? = profileImageUrl
        protected set

    fun updateProfile(nickname: String, email: String?) {
        this.nickname = nickname
        this.email = email
    }

    fun updateNickname(nickname: String) {
        if (nickname.isBlank() || nickname.length > MAX_NICKNAME_LENGTH) throw UserException(UserErrorCode.INVALID_NICKNAME)
        this.nickname = nickname
    }

    fun syncProviderProfile(email: String?, profileImageUrl: String?) {
        this.email = email
        this.profileImageUrl = profileImageUrl
    }

    fun changeRole(role: Role) {
        this.role = role
    }

    companion object {
        /** 사용자 프로필 이름 컬럼과 같은 상한이다. */
        const val MAX_NICKNAME_LENGTH = 50
    }
}
