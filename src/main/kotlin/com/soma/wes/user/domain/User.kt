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

@Entity
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    fun updateProfile(nickname: String, email: String?) {
        this.nickname = nickname
        this.email = email
    }

    fun changeRole(role: Role) {
        this.role = role
    }
}
