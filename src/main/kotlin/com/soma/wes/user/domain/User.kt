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
 *
 * 종류별로 테이블을 나누지 않는 이유는, 소셜 로그인 시점에는 아직 종류를 알 수 없고
 * `provider + providerId` 유일성도 두 테이블에 걸쳐서는 보장할 수 없기 때문이다.
 * 종류에 따라 달라지는 정보는 각자의 온보딩 산물이 갖는다. 작가는 [com.soma.wes.studio.domain.Studio],
 * 예비 부부는 [com.soma.wes.gallery.domain.GalleryMember]가 그 자리다.
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
     *
     * OAuth 콜백 시점에는 사진작가인지 예비 부부인지 알 방법이 없으므로 기본값을 두지 않는다.
     * 둘 중 하나를 기본값으로 정하면 반대쪽 사용자가 조용히 잘못된 종류로 가입된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var userType: UserType? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 온보딩에서 종류를 정했는지. */
    val isOnboarded: Boolean
        get() = userType != null

    fun updateProfile(nickname: String, email: String?) {
        this.nickname = nickname
        this.email = email
    }

    fun changeRole(role: Role) {
        this.role = role
    }

    /**
     * 온보딩에서 사용자 종류를 정한다. 한 번 정하면 바꾸지 않는다.
     *
     * 종류를 바꾸면 이미 만든 스튜디오·갤러리나 이미 수락한 초대의 주인이 사라져 데이터가 어긋난다.
     * 사진작가가 본인 결혼식 갤러리에 초대받는 경우는 종류를 바꿀 일이 아니라
     * 그 갤러리의 멤버가 되면 되는 일이므로, 변경 수단이 없어도 막히지 않는다.
     */
    fun selectType(userType: UserType) {
        // 같은 종류로 다시 정하는 것은 변경이 아니다. 스튜디오 생성이 종류를 확정하는데,
        // 그 둘이 한 트랜잭션이 아니었던 과거 데이터나 중간에 실패한 요청 때문에
        // "PHOTOGRAPHER인데 스튜디오는 없는" 계정이 생길 수 있다. 여기서 막아버리면
        // 그 계정은 영영 스튜디오를 만들지 못한다.
        if (this.userType == userType) {
            return
        }
        if (this.userType != null) {
            throw UserException(UserErrorCode.USER_TYPE_ALREADY_SELECTED)
        }
        this.userType = userType
    }
}
