package com.soma.wes.user.domain

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.global.BaseEntity
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
    @Column(nullable = false, updatable = false)
    val provider: OAuthProvider,

    @Column(nullable = false, updatable = false)
    val providerId: String,

    @Column(nullable = false, length = 50)
    var nickname: String,

    @Column
    var email: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: Role = Role.USER,

) : BaseEntity() {

    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null

    @Column(name = "suspended_at")
    var suspendedAt: ZonedDateTime? = null

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 사용자입니다." }

    fun updateProfile(nickname: String, email: String?) {
        this.nickname = nickname
        this.email = email
    }

    fun updateNickname(nickname: String) {
        this.nickname = nickname
    }

    fun syncProviderEmail(email: String?) {
        this.email = email
    }

    fun changeRole(role: Role) {
        this.role = role
    }

}
