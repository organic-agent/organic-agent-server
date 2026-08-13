package com.soma.wes.user.domain

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.global.BaseEntity
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 서비스의 단일 신원. 사진작가든 예비 부부든 계정은 이 테이블 하나다.
 */
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

    /**
     * 아직 온보딩을 마치지 않은 사용자는 `null`이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var userType: UserType? = null,

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

    /**
     * 온보딩에서 사용자 종류를 정한다. 한 번 정하면 바꾸지 않는다.
     */
    fun selectType(userType: UserType) {
        if (this.userType == userType) {
            return
        }
        if (this.userType != null) {
            throw UserException(UserErrorCode.USER_TYPE_ALREADY_SELECTED)
        }
        this.userType = userType
    }
}
